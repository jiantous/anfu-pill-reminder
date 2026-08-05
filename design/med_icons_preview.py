"""
药品图标集预览。

**直接执行 MedIconSet.kt 里的画法**：用一个迷你解释器跑那些
solid/roundRect/circle/capsuleLeft/... 调用，把结果转成 SVG 渲染。
这样预览与 Kotlin 源码同一个数据源，不会出现"手抄副本和实现不一致"的骗人情况。

只支持 MedIconSet.kt 实际用到的那几个构造，够用即可。
"""
import io
import math
import os
import re

from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics import renderPDF
from svglib.svglib import svg2rlg

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "jian",
                  "pillreminder", "ui", "components", "MedIconSet.kt")

INK = "#2A4A3E"
BG = "#E6F3EC"


# ---- 复刻 Kotlin 里的路径工具，返回 SVG 的 d 字符串片段 ----

def circle(cx, cy, r):
    return (f"M{cx},{cy - r} a{r},{r} 0 1,1 0,{2 * r} "
            f"a{r},{r} 0 1,1 0,{-2 * r} Z")


def round_rect(x0, y0, x1, y1, r):
    return (f"M{x0 + r},{y0} L{x1 - r},{y0} a{r},{r} 0 0,1 {r},{r} "
            f"L{x1},{y1 - r} a{r},{r} 0 0,1 {-r},{r} L{x0 + r},{y1} "
            f"a{r},{r} 0 0,1 {-r},{-r} L{x0},{y0 + r} a{r},{r} 0 0,1 {r},{-r} Z")


def round_top(x0, y0, x1, y1, r):
    return (f"M{x0},{y1} L{x0},{y0 + r} a{r},{r} 0 0,1 {r},{-r} "
            f"L{x1 - r},{y0} a{r},{r} 0 0,1 {r},{r} L{x1},{y1} Z")


def round_bottom(x0, y0, x1, y1, r):
    return (f"M{x0},{y0} L{x1},{y0} L{x1},{y1 - r} a{r},{r} 0 0,1 {-r},{r} "
            f"L{x0 + r},{y1} a{r},{r} 0 0,1 {-r},{-r} Z")


def capsule_left(cx, cy, r, xm):
    return (f"M{xm},{cy - r} L{cx},{cy - r} a{r},{r} 0 1,0 0,{2 * r} "
            f"L{xm},{cy + r} Z")


def capsule_right(cx, cy, r, xm):
    return (f"M{xm},{cy - r} L{cx},{cy - r} a{r},{r} 0 1,1 0,{2 * r} "
            f"L{xm},{cy + r} Z")


HELPERS = {
    "circle": circle,
    "roundRect": round_rect,
    "roundTop": round_top,
    "roundBottom": round_bottom,
    "capsuleLeft": capsule_left,
    "capsuleRight": capsule_right,
}


def parse_kt():
    """从 Kotlin 源码里抽出每个图标的块面列表。"""
    src = io.open(KT, encoding="utf-8").read()

    consts = {}
    for m in re.finditer(r"private const val (\w+)[^=]*=\s*([0-9.]+)f?", src):
        consts[m.group(1)] = float(m.group(2))

    def num(expr):
        """求值形如 12f - SEAM / 2 / 8.6f + SEAM 的简单算式。"""
        e = expr.strip()
        for k, v in consts.items():
            e = re.sub(rf"\b{k}\b", repr(v), e)
        e = e.replace("f", "")
        return float(eval(e, {"__builtins__": {}}, {}))

    icons = []
    # 每个 private val IcXxx: ImageVector = medIcon("..") { ... } 到下一个 // ---- 为止
    blocks = re.split(r"\n// ---- ", src)
    for blk in blocks[1:]:
        # 注释首行形如 "3. 颗粒/粉剂：药袋，上下封口 ..."，标签只取序号和主名
        head = blk.split("----")[0].strip()
        title = re.split(r"[：:，,（(]", head)[0].strip()
        body_m = re.search(r'medIcon\("(\w+)"\)\s*\{(.*?)\n\}\n', blk, re.S)
        if not body_m:
            continue
        body = body_m.group(2)

        rotate = 0.0
        g = re.search(r"group\(rotate = (-?[\d.]+)f", body)
        if g:
            rotate = float(g.group(1))

        faces = []
        # 每个 solid(...) { ... } 块。花括号要配对计数：
        # 内层的 roundRect(...) 不带花括号，但 group{} 会嵌套，
        # 用非贪婪正则会在第一个 } 处截断（曾导致"块面=0"的错误预览）。
        for sm in re.finditer(r"solid\(([^)]*)\)\s*\{", body):
            args = sm.group(1)
            start = sm.end()          # 紧跟在 { 之后
            depth = 1
            i = start
            while i < len(body) and depth > 0:
                if body[i] == "{":
                    depth += 1
                elif body[i] == "}":
                    depth -= 1
                i += 1
            inner = body[start:i - 1]
            alpha = consts["MAIN"]
            if "SUB" in args:
                alpha = consts["SUB"]
            evenodd = "evenOdd = true" in args

            ds = []
            for call in re.finditer(
                r"\b(circle|roundRect|roundTop|roundBottom|capsuleLeft|capsuleRight)\("
                r"([^()]*(?:\([^()]*\)[^()]*)*)\)", inner
            ):
                fn = HELPERS[call.group(1)]
                raw = call.group(2)
                vals = []
                for part in raw.split(","):
                    part = re.sub(r"^\s*\w+\s*=", "", part)
                    vals.append(num(part))
                ds.append(fn(*vals))

            # 没走 helper 的手写路径（圆片的 arcTo、液滴的 curveTo）
            if not ds:
                ds.append(convert_inline(inner, num))

            faces.append((alpha, evenodd, " ".join(ds)))
        icons.append((title, rotate, faces))
    return icons


def convert_inline(inner, num):
    """把 moveTo/lineTo/arcTo/arcToRelative/curveTo/close 逐条转成 SVG 命令。"""
    out = []
    for m in re.finditer(r"\b(moveTo|lineTo|arcToRelative|arcTo|curveTo|close)\("
                         r"([^)]*)\)|\bclose\(\)", inner):
        cmd = m.group(1)
        if cmd is None or cmd == "close":
            out.append("Z")
            continue
        args = [a for a in m.group(2).split(",") if a.strip()]
        v = []
        for a in args:
            a = a.strip()
            if a in ("true", "false"):
                v.append(1 if a == "true" else 0)
            else:
                v.append(num(a))
        if cmd == "moveTo":
            out.append(f"M{v[0]},{v[1]}")
        elif cmd == "lineTo":
            out.append(f"L{v[0]},{v[1]}")
        elif cmd == "curveTo":
            out.append(f"C{v[0]},{v[1]} {v[2]},{v[3]} {v[4]},{v[5]}")
        elif cmd == "arcTo":
            # arcTo(rx, ry, rotation, largeArc, sweep, x, y) —— Compose 的绝对弧
            out.append(f"A{v[0]},{v[1]} {v[2]} {int(v[3])},{int(v[4])} {v[5]},{v[6]}")
        elif cmd == "arcToRelative":
            out.append(f"a{v[0]},{v[1]} {v[2]} {int(v[3])},{int(v[4])} {v[5]},{v[6]}")
    return " ".join(out)


def build_svg(rotate, faces):
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
        'viewBox="0 0 24 24">',
        f'<rect width="24" height="24" fill="{BG}"/>',
    ]
    if rotate:
        parts.append(f'<g transform="rotate({rotate} 12 12)">')
    for alpha, evenodd, d in faces:
        rule = ' fill-rule="evenodd"' if evenodd else ""
        parts.append(f'<path d="{d}" fill="{INK}" fill-opacity="{alpha}"{rule}/>')
    if rotate:
        parts.append("</g>")
    parts.append("</svg>")
    return "\n".join(parts)


def render(rotate, faces, size=256):
    import fitz

    sp = os.path.join(HERE, "_ic.svg")
    pp = os.path.join(HERE, "_ic.pdf")
    with open(sp, "w", encoding="utf-8") as f:
        f.write(build_svg(rotate, faces))
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


def font(sz):
    for p in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def main():
    icons = parse_kt()
    print(f"解析到 {len(icons)} 个图标")
    for t, rot, faces in icons:
        print(f"  {t}  rotate={rot}  块面={len(faces)}")

    cell, pad, lab = 240, 24, 40
    cols = 5
    rows = (len(icons) + cols - 1) // cols
    W = pad + cols * (cell + pad)
    H = pad + rows * (cell + lab + pad)
    sheet = Image.new("RGB", (W, H), "white")
    sd = ImageDraw.Draw(sheet)
    f = font(25)

    imgs = []
    for i, (title, rot, faces) in enumerate(icons):
        im = render(rot, faces)
        imgs.append(im)
        r, c = divmod(i, cols)
        x, y = pad + c * (cell + pad), pad + r * (cell + lab + pad)
        sheet.paste(im.resize((cell, cell), Image.LANCZOS), (x, y))
        sd.text((x + cell // 2, y + cell + 6), title, fill=(40, 40, 40), font=f, anchor="ma")

    out = os.path.join(HERE, "med_icons_preview.png")
    sheet.save(out)
    print("saved", out, sheet.size)

    # 小尺寸：卡片徽标上实际约 25px，看是否还认得出
    small = Image.new("RGB", (len(icons) * 86, 116), "white")
    smd = ImageDraw.Draw(small)
    f2 = font(19)
    for i, (title, rot, faces) in enumerate(icons):
        small.paste(imgs[i].resize((50, 50), Image.LANCZOS), (i * 86 + 18, 18))
        smd.text((i * 86 + 43, 80), title.split()[-1], fill=(40, 40, 40), font=f2, anchor="ma")
    out2 = os.path.join(HERE, "med_icons_small.png")
    small.save(out2)
    print("saved", out2)


if __name__ == "__main__":
    main()
