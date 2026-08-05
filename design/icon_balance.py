"""
测量启动图标内容在 108x108 画布上的实际留白，并给出居中所需的偏移。

不手算三角函数：把 XML 里的 pathData 原样渲染成图，再按像素找内容包围盒。
胶囊是绕 -32° 旋转的圆头矩形，手算它的外接盒容易出错，量出来最实在。

用法：
    python icon_balance.py            # 只测量当前状态
    python icon_balance.py --compare  # 额外输出"当前 vs 居中"的对比图
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics import renderPDF
from svglib.svglib import svg2rlg

HERE = os.path.dirname(os.path.abspath(__file__))
FG = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable",
                  "ic_launcher_foreground.xml")
AND = "{http://schemas.android.com/apk/res/android}"

CANVAS = 108.0
RENDER = 1080          # 每单位 10 像素，测量精度 0.1
SCALE = RENDER / CANVAS


def load_paths():
    """取出 (pathData, group 旋转变换)。颜色不关心——测量只看形状。"""
    items = []

    def walk(node, transform):
        for child in node:
            tag = child.tag.split("}")[-1]
            if tag == "group":
                px = float(child.get(AND + "pivotX", 0))
                py = float(child.get(AND + "pivotY", 0))
                rot = float(child.get(AND + "rotation", 0))
                walk(child, f"rotate({rot} {px} {py})")
            elif tag == "path":
                items.append((child.get(AND + "pathData"), transform))

    walk(ET.parse(FG).getroot(), None)
    return items


def build_mask_svg(items, dy=0.0):
    """
    渲染成"黑底白形状"，便于按像素测量。
    半透明的第三个点也画成不透明白——它同样是内容的一部分，要算进包围盒。
    [dy] 是整体垂直偏移，用于预览居中后的效果。
    """
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
        'viewBox="0 0 108 108">',
        '<rect width="108" height="108" fill="#000000"/>',
    ]
    if dy:
        parts.append(f'<g transform="translate(0 {dy})">')
    for d, tf in items:
        t = f' transform="{tf}"' if tf else ""
        parts.append(f'<path d="{d}" fill="#FFFFFF"{t}/>')
    if dy:
        parts.append("</g>")
    parts.append("</svg>")
    return "\n".join(parts)


def render_svg(svg, size):
    import fitz

    sp, pp = os.path.join(HERE, "_bal.svg"), os.path.join(HERE, "_bal.pdf")
    with open(sp, "w", encoding="utf-8") as f:
        f.write(svg)
    renderPDF.drawToFile(svg2rlg(sp), pp)
    doc = fitz.open(pp)
    zoom = size / max(doc[0].rect.width, doc[0].rect.height)
    pix = doc[0].get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False)
    img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    doc.close()
    os.remove(sp)
    os.remove(pp)
    s = min(img.size)
    return img.crop((0, 0, s, s))


def measure(items, dy=0.0):
    """返回内容包围盒（画布单位）。"""
    img = render_svg(build_mask_svg(items, dy), RENDER).convert("L")
    # 阈值 40：抗锯齿边缘算进内容，纯黑背景排除在外
    bbox = img.point(lambda v: 255 if v > 40 else 0).getbbox()
    if bbox is None:
        raise SystemExit("测不到内容，渲染可能失败")
    x0, y0, x1, y1 = (v / SCALE for v in bbox)
    return x0, y0, x1, y1


def report(items):
    x0, y0, x1, y1 = measure(items)
    top, bottom = y0, CANVAS - y1
    left, right = x0, CANVAS - x1

    print("内容包围盒（画布单位，共 108）")
    print(f"  x: {x0:6.2f} .. {x1:6.2f}   宽 {x1 - x0:5.2f}")
    print(f"  y: {y0:6.2f} .. {y1:6.2f}   高 {y1 - y0:5.2f}")
    print()
    print("四周留白")
    print(f"  上 {top:5.2f}    下 {bottom:5.2f}    差 {bottom - top:+5.2f}")
    print(f"  左 {left:5.2f}    右 {right:5.2f}    差 {right - left:+5.2f}")
    print()

    shift = (bottom - top) / 2
    print(f"内容中心 y = {(y0 + y1) / 2:.2f}（画布中心 54.00）")
    if abs(shift) < 0.2:
        print("上下已经基本对称，不用调。")
    else:
        direction = "下移" if shift > 0 else "上移"
        print(f"要让上下留白相等：整体{direction} {abs(shift):.2f}")
        print(f"  → 胶囊 group 的 pivotY 与各 y 坐标、三个点的 y 都加 {shift:+.2f}")

    # 顺带核对安全区占比：Pixel 规范里安全区半径 33
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half_w, half_h = (x1 - x0) / 2, (y1 - y0) / 2
    outer = (half_w ** 2 + half_h ** 2) ** 0.5
    print()
    print(f"内容外接半径 {outer:.2f} / 安全区半径 33 = {outer / 33 * 100:.1f}%")
    return shift


def font(sz):
    for p in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def build_color_svg(items, dy=0.0):
    """带真实配色的渲染，用于给人看的对比图。"""
    tree = ET.parse(FG).getroot()
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
        'viewBox="0 0 108 108">',
        '<defs><linearGradient id="bg" x1="54" y1="0" x2="54" y2="108" '
        'gradientUnits="userSpaceOnUse">'
        '<stop offset="0" stop-color="#4F9A7D"/>'
        '<stop offset="1" stop-color="#3A7D66"/></linearGradient></defs>',
        '<rect width="108" height="108" fill="url(#bg)"/>',
    ]
    if dy:
        parts.append(f'<g transform="translate(0 {dy})">')

    def walk(node, transform):
        for child in node:
            tag = child.tag.split("}")[-1]
            if tag == "group":
                px = float(child.get(AND + "pivotX", 0))
                py = float(child.get(AND + "pivotY", 0))
                rot = float(child.get(AND + "rotation", 0))
                walk(child, f"rotate({rot} {px} {py})")
            elif tag == "path":
                a = [f'd="{child.get(AND + "pathData")}"',
                     f'fill="{child.get(AND + "fillColor")}"']
                if child.get(AND + "fillAlpha"):
                    a.append(f'fill-opacity="{child.get(AND + "fillAlpha")}"')
                if transform:
                    a.append(f'transform="{transform}"')
                parts.append("<path " + " ".join(a) + "/>")

    walk(tree, None)
    if dy:
        parts.append("</g>")
    parts.append("</svg>")
    return "\n".join(parts)


def circle_crop(img, guides=None):
    """裁成 Pixel 默认的圆形，只露中心 72/108。可叠加参考线。"""
    full = img.size[0]
    crop = int(full * 72 / 108)
    off = (full - crop) // 2
    view = img.crop((off, off, off + crop, off + crop)).resize((512, 512), Image.LANCZOS)

    mask = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 511, 511], fill=255)
    out = Image.new("RGB", (512, 512), (245, 245, 247))
    out.paste(view, (0, 0), mask)

    if guides:
        d = ImageDraw.Draw(out)
        # 画布中心横线
        d.line([(0, 256), (511, 256)], fill=(255, 90, 90), width=2)
        # 内容上下边界（换算到这个 512 视图）
        y0, y1 = guides
        for y in (y0, y1):
            py = (y - 18) / 72 * 512      # 视图只含 18..90
            if 0 <= py <= 511:
                d.line([(0, py), (511, py)], fill=(90, 160, 255), width=2)
    return out


def compare(items, shift):
    # 除了几何居中，再给一档"视觉居中"：
    # 三个点小而分散、视觉重量远低于实心胶囊，按包围盒居中后"重"的胶囊
    # 会显得偏上。经验做法是比几何居中少下移一些。
    variants = [
        ("当前", 0.0),
        (f"视觉居中（下移 {shift * 0.6:.2f}）", shift * 0.6),
        (f"几何居中（下移 {shift:.2f}）", shift),
    ]
    cell, pad, head = 512, 30, 56
    W = pad + len(variants) * (cell + pad)
    H = head + cell + pad + 40
    sheet = Image.new("RGB", (W, H), "white")
    sd = ImageDraw.Draw(sheet)
    f_t, f_s = font(32), font(24)

    for i, (name, dy) in enumerate(variants):
        x0, y0, x1, y1 = measure(items, dy)
        img = render_svg(build_color_svg(items, dy), 864)
        x = pad + i * (cell + pad)
        sd.text((x + cell // 2, 12), name, fill=(20, 20, 20), font=f_t, anchor="ma")
        sheet.paste(circle_crop(img, guides=(y0, y1)), (x, head))
        sd.text((x + cell // 2, head + cell + 8),
                f"上 {y0:.2f}   下 {CANVAS - y1:.2f}",
                fill=(110, 110, 110), font=f_s, anchor="ma")

    sd.text((pad, H - 26),
            "红线＝画布中心   蓝线＝内容上下边界",
            fill=(120, 120, 120), font=f_s)
    p = os.path.join(HERE, "icon_balance_compare.png")
    sheet.save(p)
    print("\nsaved", p)


if __name__ == "__main__":
    paths = load_paths()
    print(f"读到 {len(paths)} 条 path\n")
    s = report(paths)
    if "--compare" in sys.argv and abs(s) >= 0.2:
        compare(paths, s)
