"""生成《安服使用说明》PDF。

设计取向：给非技术用户看的说明书——先讲能解决什么问题，再讲怎么用，
截图配文字说明。所有截图都是真机实拍，不用示意图。
截图里的药品与记录都是演示数据（见 design/make_demo_data.py），
不含真实用药信息——这份 PDF 会公开发布。

技术细节刻意不写进来：那部分给开发者看，放在 README。
"""
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image, Table, TableStyle,
    PageBreak, KeepTogether
)
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT

DOCS = os.path.dirname(os.path.abspath(__file__))
# 用 screenshots 里的 JPEG 而非 shots 里的 PNG：
# 截图在文档里最大只占 62mm 宽，JPEG 质量足够，而整套 PNG 有 2.4MB、
# 嵌进 PDF 后文件大 0.5MB，对一份要给人下载的说明书不值得。
SHOTS = os.path.join(DOCS, "screenshots")
OUT = os.path.join(DOCS, "安服使用说明.pdf")

# ---- 中文字体 ----
FONT_CANDIDATES = [
    ("MSYH", "C:/Windows/Fonts/msyh.ttc", 0),
    ("MSYHBD", "C:/Windows/Fonts/msyhbd.ttc", 0),
]
FONT_REG, FONT_BOLD = None, None
for name, path, idx in FONT_CANDIDATES:
    if os.path.exists(path):
        try:
            pdfmetrics.registerFont(TTFont(name, path, subfontIndex=idx))
            if FONT_REG is None:
                FONT_REG = name
            else:
                FONT_BOLD = name
        except Exception as e:
            print(f"字体 {name} 注册失败: {e}")
if FONT_REG is None:
    raise SystemExit("找不到可用的中文字体")
if FONT_BOLD is None:
    FONT_BOLD = FONT_REG

# ---- 配色（与 App 的鼠尾草绿一致）----
GREEN = colors.HexColor("#3A7D66")
GREEN_LIGHT = colors.HexColor("#E6F3EC")
INK = colors.HexColor("#1A1C1A")
GRAY = colors.HexColor("#5A625C")
LINE = colors.HexColor("#D6DED9")

S = {
    "title": ParagraphStyle("title", fontName=FONT_BOLD, fontSize=30, leading=38,
                            textColor=GREEN, alignment=TA_CENTER, spaceAfter=6),
    "subtitle": ParagraphStyle("subtitle", fontName=FONT_REG, fontSize=13, leading=20,
                               textColor=GRAY, alignment=TA_CENTER, spaceAfter=4),
    "h1": ParagraphStyle("h1", fontName=FONT_BOLD, fontSize=18, leading=26,
                         textColor=GREEN, spaceBefore=18, spaceAfter=9,
                         keepWithNext=1),
    "h2": ParagraphStyle("h2", fontName=FONT_BOLD, fontSize=13.5, leading=21,
                         textColor=INK, spaceBefore=11, spaceAfter=5,
                         keepWithNext=1),
    "body": ParagraphStyle("body", fontName=FONT_REG, fontSize=10.5, leading=18,
                           textColor=INK, spaceAfter=5),
    "note": ParagraphStyle("note", fontName=FONT_REG, fontSize=9.5, leading=16,
                           textColor=GRAY, spaceAfter=4),
    "cap": ParagraphStyle("cap", fontName=FONT_REG, fontSize=9, leading=14,
                          textColor=GRAY, alignment=TA_CENTER, spaceBefore=3),
    "cell": ParagraphStyle("cell", fontName=FONT_REG, fontSize=9.5, leading=15,
                           textColor=INK),
    "cellb": ParagraphStyle("cellb", fontName=FONT_BOLD, fontSize=9.5, leading=15,
                            textColor=INK),
}


def P(t, s="body"):
    return Paragraph(t, S[s])


def shot(name, width=72 * mm, caption=None):
    """
    插入一张手机截图，等比缩放。

    图与说明用 KeepTogether 绑在一起（说明跑到下一页会读不懂），
    但仅此一层——不要再往外层套，否则整段文字会被一起拖到下一页。
    """
    path = os.path.join(SHOTS, name)
    if not os.path.exists(path):
        return P(f"（缺少截图：{name}）", "note")
    from PIL import Image as PILImage
    with PILImage.open(path) as im:
        w, h = im.size
    img = Image(path, width=width, height=width * h / w)
    if caption:
        return KeepTogether([img, P(caption, "cap")])
    return img


def shots_row(items, width=52 * mm):
    """
    并排放多张截图。items = [(文件名, 说明), ...]

    宽度刻意不大：手机截图是 1080x2520 的长条，宽 64mm 时高就有 149mm，
    两张并排占掉页面近一半，而 Table 是不可分割块——放不下就整块跳到下一页，
    把前一页底部空出一大截。52mm 高约 121mm，容易塞进页面剩余空间。
    """
    cells, caps = [], []
    for name, cap in items:
        cells.append(shot(name, width))
        caps.append(P(cap, "cap"))
    t = Table([cells, caps], colWidths=[width + 6 * mm] * len(items))
    t.setStyle(TableStyle([
        ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 1), (-1, 1), 4),
    ]))
    return t


def kv_table(rows, col1=38 * mm):
    """两列表格：左标签右内容。"""
    data = [[P(a, "cellb"), P(b, "cell")] for a, b in rows]
    t = Table(data, colWidths=[col1, 168 * mm - col1])
    t.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -2), 0.4, LINE),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
    ]))
    return t


def feature_table(rows):
    """三列功能表：功能 / 说明 / 在哪。"""
    head = [P("功能", "cellb"), P("说明", "cellb"), P("在哪里", "cellb")]
    data = [head] + [[P(a, "cell"), P(b, "cell"), P(c, "cell")] for a, b, c in rows]
    t = Table(data, colWidths=[30 * mm, 100 * mm, 38 * mm], repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), GREEN_LIGHT),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.4, LINE),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
    ]))
    return t


def tip(text, tone="info"):
    """提示框。"""
    bg = GREEN_LIGHT if tone == "info" else colors.HexColor("#FDECEA")
    bd = GREEN if tone == "info" else colors.HexColor("#C0392B")
    t = Table([[P(text, "note")]], colWidths=[168 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), bg),
        ("LINEBEFORE", (0, 0), (0, -1), 2.5, bd),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
    ]))
    return t


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont(FONT_REG, 8)
    canvas.setFillColor(GRAY)
    canvas.drawString(21 * mm, 12 * mm, "安服 · 服药提醒")
    canvas.drawRightString(A4[0] - 21 * mm, 12 * mm, f"第 {doc.page} 页")
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.4)
    canvas.line(21 * mm, 16 * mm, A4[0] - 21 * mm, 16 * mm)
    canvas.restoreState()


story = []

# ===== 封面 =====
story += [
    Spacer(1, 14 * mm),
    P("安 服", "title"),
    P("Android 服药提醒应用 · 使用说明", "subtitle"),
    Spacer(1, 4 * mm),
    P("版本 1.0　·　2026 年 8 月", "subtitle"),
    Spacer(1, 8 * mm),
]
story.append(shots_row([
    ("01_today.jpg", "今天：该吃什么，一眼看清"),
    ("03_stats.jpg", "统计：按时服药率与日历"),
], width=62 * mm))
story += [
    Spacer(1, 8 * mm),
    tip("「安服」取「安心服药」之意。它做一件事：让你或家人不漏服、不错时。"
        "所有数据只存在手机本地，不联网、不上传、无需注册账号。"),
]

story.append(PageBreak())

# ===== 一、这个应用解决什么问题 =====
story += [
    P("一、这个应用解决什么问题", "h1"),
    P("长期服药最常见的三个麻烦：<b>忘记吃</b>、<b>记不清吃过没有</b>、<b>药快用完了不知道</b>。"
      "安服针对这三点设计：", "body"),
    Spacer(1, 2 * mm),
    kv_table([
        ("忘记吃", "到点用系统闹钟提醒，通知栏上直接有「已服用 / 稍后提醒 / 跳过」三个按钮，"
                "不用打开应用就能打卡。手机重启后提醒不会丢。"),
        ("记不清", "「今天」页把当天要吃的药按「已错过 / 待服用 / 已完成」分组，"
                "打过卡的会显示实际服用时间。"),
        ("药用完", "可以记录每种药剩多少，每次打卡自动扣减，低于设定值时提醒续药。"),
        ("复杂用法", "支持每天、按周固定几天、隔 N 天、吃 X 天停 Y 天四种周期，"
                 "还能设疗程起止日期和饭前饭后。"),
        ("懒得手输", "拍一张药品说明书，自动识别药名、剂量、每日次数、饭前饭后并填进表单。"),
        ("换手机", "一键导出备份文件，新手机导入即可恢复，包括所有历史记录。"),
    ]),
    Spacer(1, 5 * mm),
    P("适用人群", "h2"),
    P("需要长期或周期性服药的人；也适合帮父母长辈设好提醒后交给他们使用——"
      "界面只有三个标签页，字号偏大，操作路径短。", "body"),
]

story += [
    Spacer(1, 6 * mm),
    P("二、安装", "h1"),
    P("系统要求", "h2"),
    P("Android 7.0 及以上。安装包按手机芯片分成几个版本，选对了体积更小：", "body"),
    Spacer(1, 2 * mm),
    feature_table([
        ("arm64 版", "约 16 MB。2017 年之后的手机几乎都是这个类型，优先用它。", "推荐"),
        ("通用版", "约 45 MB。包含所有芯片类型，不确定对方手机时用这个，装上一定能跑。", "兜底"),
    ]),
    Spacer(1, 4 * mm),
    P("安装步骤", "h2"),
    P("1. 把 APK 文件传到手机（微信发给自己、数据线拷贝、网盘下载都可以）<br/>"
      "2. 在手机上点开这个文件<br/>"
      "3. 如果提示「不允许安装未知来源应用」，按提示去设置里允许一次即可<br/>"
      "4. 装好后桌面会出现绿色胶囊图标的「安服」", "body"),
    Spacer(1, 4 * mm),
    tip("这个应用没有上架应用商店，是直接安装的。它不含广告、不联网、不收集任何信息，"
        "所需权限只有三项：发送通知、精确闹钟、相机（仅在你使用拍说明书功能时）。"),
]


# ===== 三、第一次打开 =====
story += [
    P("三、第一次打开：把提醒设置好", "h1"),
    P("首次启动会出现一个引导页，让你打开三项系统设置。<b>这一步很重要</b>——"
      "手机的省电机制会冻结后台应用，不设置的话该吃药时可能收不到提醒。", "body"),
    Spacer(1, 3 * mm),
    feature_table([
        ("允许发送通知", "没有这项，到点了不会有任何提示。", "点「设置」即可"),
        ("允许精确闹钟", "没有这项，提醒可能延迟几分钟到几十分钟。", "点「设置」即可"),
        ("电池设为「无限制」", "手机省电时会冻结后台，提醒可能被延后甚至跳过整天。",
         "点「设置」后在页面里选「电池」→「无限制」"),
    ]),
    Spacer(1, 4 * mm),
    P("引导页会<b>自动识别你的手机品牌</b>，给出对应的额外提示。例如小米还需要开自启动、"
      "索尼建议关掉 STAMINA 模式对它的限制。", "body"),
    Spacer(1, 3 * mm),
    tip("如果当时跳过了没设，以后首页顶部会出现橙红色横幅提醒你，点一下就能去设置。"
        "三项都设好后横幅自动消失，不会一直啰嗦。"),
    Spacer(1, 6 * mm),

    # ===== 四、日常使用 =====
    # 截图刻意放在章节靠后：截图组是不可分割块，若紧跟在标题后面，
    # 页面剩余空间常放不下，整块跳页会把上一页底部空掉三分之一。
    P("四、日常使用", "h1"),
    P("底部三个标签页：<b>今天</b>（该吃什么）、<b>我的药箱</b>（管理药品）、"
      "<b>统计</b>（看历史和服药率）。", "body"),
    Spacer(1, 2 * mm),
    P("4.1　打卡：记录吃过了", "h2"),
    P("在「今天」页，每条药右侧有个<b>圆圈</b>，点一下变成绿色对勾表示已服用；再点一下可以撤销。"
      "也可以用卡片下方的「已服用」「跳过这次」按钮。", "body"),
    P("打过卡的会移到「已完成」分组，并显示实际服用时间（例如「已于 08:42 服用」）。"
      "过了时间还没操作的会归到「已错过」分组，用红色标出。", "body"),
    Spacer(1, 3 * mm),
    P("4.2　从通知栏直接打卡", "h2"),
    P("到点后手机会弹出提醒通知，上面有三个按钮：", "body"),
    kv_table([
        ("已服用", "直接记录已吃，同时自动扣减库存。不用打开应用。"),
        ("稍后提醒", "延后 10 分钟再响一次。适合手上正忙的时候。"),
        ("跳过", "记录为主动跳过，不计入漏服。"),
    ], col1=28 * mm),
    Spacer(1, 3 * mm),
    P("4.3　看别的日期", "h2"),
    P("「今天」页右上角的左右箭头可以翻看前后几天。翻走之后会出现「回到今天」按钮。", "body"),
    Spacer(1, 4 * mm),
]
story.append(shots_row([
    ("01_today.jpg", "今天：分组显示 + 顶部进度"),
    ("02_meds.jpg", "我的药箱：药品与库存一览"),
], width=62 * mm))


# ===== 五、添加药品 =====
story += [
    P("五、添加药品", "h1"),
    P("在「今天」或「我的药箱」页点右下角的<b>「+ 加药」</b>。", "body"),
    Spacer(1, 2 * mm),
    P("5.1　手动填写", "h2"),
    kv_table([
        ("基本信息", "药品名称、单次剂量与单位（片 / 粒 / mL 都可以自己填）、备注。"),
        ("服药时间", "可以加多个时间点，比如早晚各一次。点已有时间可以修改，点 × 删除。"
                 "还能标注饭前 / 随餐 / 饭后 / 空腹。"),
        ("用药频率", "四选一：<b>每天</b>；<b>按周</b>（点选周一到周日）；"
                 "<b>间隔</b>（填 2 就是隔天吃）；<b>周期</b>（吃 X 天停 Y 天，适合避孕药等）。"),
        ("疗程", "开始日期决定间隔和周期从哪天算起。可以设结束日期，到期自动停止提醒。"),
        ("提醒与库存", "可以单独关掉某种药的通知；填了库存后每次打卡自动扣减，"
                  "低于「续药提醒线」时提醒你买药。"),
        ("图标与颜色", "8 种配色、10 种按剂型分类的图标（圆片、胶囊、颗粒、口服液、滴剂、注射、喷雾、软膏、贴剂等），多种药一眼区分。填了单位还会自动推荐对应图标。"),
    ]),
    Spacer(1, 4 * mm),
    P("5.2　拍说明书自动填写", "h2"),
    P("在添加药品页顶部点<b>「拍药品说明书自动填写」</b>，把说明书的「用法用量」那段对准框内拍照，"
      "或从相册选一张已拍好的照片。", "body"),
    P("识别完成后会列出提取到的信息，<b>每一项都标明依据</b>（从哪句话得来的），"
      "没识别出的会写「未识别，需你手填」。确认后点「填入表单」，值会预填好，你还能继续修改，"
      "<b>点保存才真正存进去</b>。", "body"),
    Spacer(1, 3 * mm),
    tip("识别全程在手机本地完成，<b>不联网、不上传、不保存照片</b>。<br/>"
        "机器识别可能出错，尤其药名里的生僻字。应用内置了常见形近字纠错"
        "（例如把误认的「氨气」纠正为「氨氯」），纠正过的字会明确列出来让你核对。"
        "<b>请务必以医生嘱咐和实际说明书为准。</b>"),
    Spacer(1, 4 * mm),
]
story.append(shots_row([
    ("04_edit.jpg", "添加药品：顶部有拍说明书入口"),
    ("08_scan_result.jpg", "识别结果：每项都给出依据"),
], width=54 * mm))

story += [
    P("六、看统计", "h1"),
]
story += [
    Spacer(1, 1 * mm),
    kv_table([
        ("按时服药率", "圆环显示完成百分比，右侧分别列出按时服用、错过未服、主动跳过的次数。"
                  "可切换近 7 天 / 30 天 / 90 天。"),
        ("7 天完成度", "每天一根柱子，柱子高度是当天的完成比例，一眼看出哪天漏了。"),
        ("月历", "绿色＝当天全部服用，紫色＝部分服用，红色＝有漏服。点某一天可以看当天明细。"),
    ]),
    Spacer(1, 3 * mm),
    P("这几个数字适合复诊时给医生看——比口头回忆「大概都吃了」更可靠。", "note"),
    Spacer(1, 3 * mm),
]
story.append(shot("03_stats.jpg", width=54 * mm, caption="统计页：圆环 + 柱状图 + 月历"))


# ===== 七、备份与换机 =====
story += [
    P("七、备份与换手机", "h1"),
    P("数据只存在手机本地，所以<b>换手机或手机丢了需要靠备份</b>。"
      "入口在任意页面右上角的<b>云朵图标</b>。", "body"),
    Spacer(1, 2 * mm),
    P("7.1　推荐做法：配合云盘自动同步", "h2"),
    P("1. 点云朵图标 →「选择备份文件夹」<br/>"
      "2. 选一个<b>云盘应用的自动同步目录</b>（微云、百度网盘、OneDrive 等都可以）<br/>"
      "3. 以后每次点「备份到这个文件夹」，云盘会自己把文件上传<br/>"
      "4. 换手机时装好安服，从云盘取那个文件导入即可", "body"),
    Spacer(1, 3 * mm),
    P("7.2　其它导出方式", "h2"),
    kv_table([
        ("另存为", "自己挑保存位置和文件名。"),
        ("分享", "直接发到微信收藏、发给自己，或传给任意应用。也是一种备份。"),
    ], col1=28 * mm),
    Spacer(1, 3 * mm),
    P("7.3　恢复", "h2"),
    P("点「选择备份文件并恢复」，选中备份文件后会先告诉你<b>备份里有什么</b>"
      "（几种药、几条记录、什么时候导出的）以及<b>当前手机有什么</b>，然后由你决定：", "body"),
    kv_table([
        ("完全覆盖", "丢弃这台手机上现有的数据，全部用备份里的。适合换新手机。"),
        ("合并", "两边的药和记录都保留，同一次服药以时间较新的为准。"),
    ], col1=28 * mm),
    P("恢复后所有提醒会自动重新排好，不需要手动重设。", "body"),
    Spacer(1, 4 * mm),
    tip("备份文件是普通文本，不加密，可以直接打开查看。里面只有药品和服药记录，"
        "不含任何账号信息。<b>它不会自动上传到任何服务器</b>——存到哪、给谁看，完全由你决定。"),
    Spacer(1, 3 * mm),
    tip("超过 30 天没备份时，首页会出现一条淡色提示。可以点「不再提示」永久关闭。", "info"),
    Spacer(1, 3 * mm),
]
story.append(shot("07_backup.jpg", width=54 * mm, caption="备份与换机页"))


# ===== 八、常见问题 =====
story += [
    P("八、常见问题", "h1"),
    kv_table([
        ("到点没有提醒？",
         "① 检查首页顶部有没有橙红色横幅，有就点「去设置」把三项都打开；"
         "② 确认这种药的「到点通知提醒」开关是开着的；"
         "③ 部分手机（小米、华为、OPPO、vivo 等）还需要单独允许自启动或后台运行，"
         "引导页会针对你的机型给出提示。"),
        ("提醒时间不准，晚了十几分钟？",
         "缺少「精确闹钟」权限。去首页横幅或引导页把它打开。"),
        ("卸载重装后数据没了？",
         "数据存在应用私有目录，卸载会一并清除。请养成定期导出备份的习惯。"),
        ("识别的药名有错字？",
         "机器识别难免出错。在填入表单后直接改正确即可，改完再保存。"),
        ("能不能多人共用？",
         "目前一台手机一份数据，没有多用户切换。如果要给父母用，"
         "建议在他们手机上单独装一份，你帮他们设好药品和时间。"),
        ("会不会耗电？",
         "平时不在后台运行，只在你设定的时间点被系统唤醒一次，耗电极小。"),
        ("数据会上传吗？",
         "不会。应用没有联网功能，也没有任何服务器。文字识别用的是手机本地引擎，"
         "拍下的照片识别完即丢弃，不保存。"),
    ], col1=44 * mm),
    Spacer(1, 5 * mm),
]


# ===== 九、免责说明 =====
# 刻意独立成章而不是塞在附录末尾：这是使用者必须看到的内容，
# 不该和技术参数混在一起。技术细节已移到 GitHub 的 README，日常使用不需要。
story += [
    P("九、免责说明", "h1"),
    P("安服是一个<b>辅助记录与提醒工具</b>，不是医疗器械，<b>不提供任何医疗建议</b>。"
      "使用前请了解以下几点：", "body"),
    Spacer(1, 2 * mm),
    P("<b>用药方案遵医嘱。</b>吃什么药、吃多少、吃多久，请完全按照医生嘱咐和药品说明书，"
      "本应用不对用药方案作任何判断或建议。", "body"),
    P("<b>识别结果需核对。</b>拍说明书得到的药名、剂量、次数由机器识别，可能出错，"
      "填入表单后请逐项检查。", "body"),
    P("<b>提醒不保证必达。</b>提醒依赖手机系统的闹钟与通知机制。若系统省电策略限制了本应用，"
      "或手机关机、静音、被强制停止，提醒可能延迟或不出现。"
      "请勿将本应用作为唯一的用药保障手段。", "body"),
    P("<b>记录只反映打卡。</b>打卡由你手动确认，应用无法核实你是否真的服药，"
      "统计数字不代表实际服药事实。", "body"),
    Spacer(1, 3 * mm),
    tip("如果你正在服用需要严格按时的药物（如抗凝药、免疫抑制剂、精神类药物等），"
        "请同时使用其它提醒方式作为备份，并与医生确认漏服后的处理方式。", "warn"),
]

doc = SimpleDocTemplate(
    OUT, pagesize=A4,
    leftMargin=21 * mm, rightMargin=21 * mm,
    topMargin=20 * mm, bottomMargin=22 * mm,
    title="安服 使用说明", author="安服",
    subject="Android 服药提醒应用使用说明",
)
doc.build(story, onFirstPage=footer, onLaterPages=footer)
print(f"已生成：{OUT}")
print(f"大小：{os.path.getsize(OUT)/1024:.0f} KB")
