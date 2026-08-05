"""
生成用于 README / 使用说明截图的演示备份文件。

刻意用非处方的常见保健品和明显通用的名称，不体现任何真实病情：
截图会公开到 GitHub，用药信息属于健康隐私。

数据设计成"界面各种状态都有得看"：
  · 已服用 / 待服用 / 已错过 三种状态都出现
  · 有库存告警、有多时段、有隔天与周期两种特殊频率
  · 依从率不是 100%（那样柱状图和日历看不出层次）
"""
import io
import json
import os
import random
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "demo_backup.json")

# 固定随机种子：每次生成同样的数据，截图可复现
random.seed(20260805)

TODAY = date(2026, 8, 5)


def med(mid, name, dosage, unit, note, color, icon, times,
        meal="NONE", schedule=None, stock=None, threshold=5.0,
        start_days_ago=60):
    return {
        "id": mid,
        "name": name,
        "dosage": dosage,
        "unit": unit,
        "note": note,
        "colorIndex": color,
        "iconIndex": icon,
        "schedule": schedule or {"type": "com.jian.pillreminder.data.Schedule.Daily"},
        "times": [{"hour": h, "minute": m} for h, m in times],
        "mealRelation": meal,
        "startDate": str(TODAY - timedelta(days=start_days_ago)),
        "endDate": None,
        "remindersEnabled": True,
        "stockRemaining": stock,
        "stockThreshold": threshold,
        "archived": False,
        "isSample": False,
    }


MEDS = [
    med("demo-vitd", "维生素 D3", 1.0, "粒", "随早餐服用吸收更好",
        color=5, icon=1, times=[(8, 0)], meal="WITH_MEAL", stock=42.0),
    med("demo-calcium", "碳酸钙咀嚼片", 1.0, "片", "",
        color=1, icon=0, times=[(8, 30), (20, 30)], meal="AFTER_MEAL",
        stock=6.0, threshold=8.0),          # 触发库存告警
    med("demo-fish", "深海鱼油", 2.0, "粒", "",
        color=0, icon=1, times=[(13, 0)], stock=88.0),
    med("demo-probio", "益生菌粉", 1.0, "袋", "冲水后尽快喝完，别用热水",
        color=6, icon=3, times=[(21, 0)], meal="EMPTY_STOMACH",
        schedule={"type": "com.jian.pillreminder.data.Schedule.EveryNDays",
                  "intervalDays": 2},       # 隔天
        stock=15.0),
    med("demo-eyedrop", "人工泪液", 1.0, "滴", "开封后一个月内用完",
        color=3, icon=5, times=[(10, 0), (16, 0)],
        schedule={"type": "com.jian.pillreminder.data.Schedule.WeekDays",
                  "daysOfWeek": [1, 2, 3, 4, 5]},   # 工作日
        stock=None),
]


def build_logs():
    """
    造 40 天的服药记录，依从率约 88%：
    偶尔漏一次、偶尔跳过，图表才有层次。今天只补到当前时刻之前。
    """
    logs = []
    now_hm = (12, 30)     # 假定"现在"是 12:30，之后的时刻保持待服用

    for d_off in range(39, -1, -1):
        day = TODAY - timedelta(days=d_off)
        for m in MEDS:
            # 频率判断
            sch = m["schedule"]
            kind = sch["type"].rsplit(".", 1)[-1]
            start = datetime.strptime(m["startDate"], "%Y-%m-%d").date()
            if day < start:
                continue
            days_since = (day - start).days
            if kind == "EveryNDays" and days_since % sch["intervalDays"] != 0:
                continue
            if kind == "WeekDays" and day.isoweekday() not in sch["daysOfWeek"]:
                continue

            for t in m["times"]:
                # 今天：只处理已过去的时刻
                if d_off == 0 and (t["hour"], t["minute"]) >= now_hm:
                    continue

                r = random.random()
                if r < 0.88:
                    status = "TAKEN"
                elif r < 0.95:
                    status = "SKIPPED"
                else:
                    continue          # 漏服：不写记录，界面上就是"已错过"

                # 实际服药时间在计划时刻附近浮动几分钟
                acted = datetime(day.year, day.month, day.day,
                                 t["hour"], t["minute"]) + \
                    timedelta(minutes=random.randint(-4, 18))
                logs.append({
                    "medicationId": m["id"],
                    "date": str(day),
                    "time": t,
                    "status": status,
                    "actedAtMillis": int(acted.timestamp() * 1000),
                })
    return logs


def main():
    logs = build_logs()
    backup = {
        "version": 2,
        "exportedAt": "2026-08-05T12:30:00",
        "appVersion": "1.0",
        "medications": MEDS,
        "logs": logs,
        "snoozeMinutes": 10,
    }
    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(backup, f, ensure_ascii=False, indent=2)

    taken = sum(1 for x in logs if x["status"] == "TAKEN")
    skipped = sum(1 for x in logs if x["status"] == "SKIPPED")
    print(f"写出 {OUT}")
    print(f"  药品 {len(MEDS)} 种")
    print(f"  记录 {len(logs)} 条（已服 {taken} / 跳过 {skipped}）")
    print(f"  依从率约 {taken / len(logs) * 100:.0f}%")


if __name__ == "__main__":
    main()
