# -*- coding: utf-8 -*-
"""
Lumanga 代码体检 —— 「别把项目堆成屎山」的锚定参考物。

用法（在 Lumanga 目录下）：
    python tools/code_health.py            # 只体检，输出 PASS / FAIL
    python tools/code_health.py --fix      # 额外自动清掉两类绝对安全的东西：
                                          #   1) UiStrings 里没人用的文案常量
                                          #   2) 确认没用到的 import
                                          # 其余项只报告，不自动改（怕误删）。

设计原则：宁可少报也不误报。Compose 的 `by mutableStateOf` 需要
`getValue` / `setValue` 这两个 import，虽然正文里搜不到，也绝不能删。

基线数字见 BASELINE。任何一项超过基线，脚本返回非 0，本轮就不算做完。
基线只允许在「确实新增了功能、且当场说清用途」时上调，并同步改 MEMORY.md。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "java"
UISTRINGS = SRC / "com" / "yingcolor" / "lumanga" / "UiStrings.kt"

# ---- 锚：这些数字是「干净状态」的样子，不许无声无息地长 ----
BASELINE = {
    # 2026-11-15 第 57 轮清理后的真实值，只留极小余量。
    # 上调必须当轮说明「新增了什么功能」，并同步改 .workbuddy/memory/MEMORY.md。
    # 第 61 轮上调：+1 文件 / +100 行 = 译文改用内置漫画字体
    #   （data/LetteringFont.kt 负责懒加载 + 逐字覆盖判断，drawFittedText 支持混排）。
    # 第 62 轮上调：+1 文件 / +70 行 = 「底板范围」用户选项
    #   （data/CoverStyle.kt + Prefs/UiSettings/配置页开关 + 渲染参数）。
    # 第 65 轮上调：+110 行 = 「读不出字时用 medium 补读」用户开关
    #   （Prefs/UiSettings 新键 + OcrModelPicker 开关 + PaddleOcrEngine 懒加载的 recOnly 识别器
    #   与 refillBlanks；拷贝逻辑抽成 copyAssets 供两边共用；ONNX 识别改为按内容宽度输入；
    #   补读结果带 2 字/0.5 置信度门槛，避免把画面杂点读成「2」；
    #   竖列末尾被 det 截断时下扩 3 格再读（每页最多 3 列））。
    # 第 67 轮上调：+50 行 = 「读得含糊的列用 medium 复核」
    #   （recheckGarbled：置信度 <0.75 且 ≥3 字的竖列重读，要求置信度 +0.12 且不更短才替换，
    #   每页最多 3 列；复用同一台 recOnly 识别器与同一个用户开关）。
    # 第 68 轮上调：+2 文件 / +100 行 = 修「阅读页连点卡死」的内存根因
    #   （data/CacheBudget.kt 把 7 个缓存上限收成一份按堆算的预算；data/MemoryPressure.kt
    #   把系统内存压力回调接成一次清理。原先缓存配额合计 213MB / 256MB 堆，翻页时分配
    #   17MB 整页位图必失败 → 全量 GC → OutOfMemoryError → 输入 5 秒没人处理（ANR）。
    #   ReaderScreen.kt 本身只留引用，单文件行数反而没超。）
    # 第 70 轮上调：+50 行 = 「译文不越出气球」
    #   （typeBounds() 把排版范围夹到气球轮廓外接框、overflowOf()/contourBounds() 观测越界像素；
    #   只影响原本就溢出的组，已经放得下的组排版完全不变）。
    # 第 72 轮上调：+50 行 = 修「轮廓只盖住半截时底板被裁掉、上半截原文裸露」
    #   （contourHoldsText() 判断轮廓是否真的罩住这组文字；不罩住就不再把排版框夹进轮廓、
    #   也不拿它当底板的裁剪路径。copin 第 33 页右侧长条：文字 y 114..1501、轮廓 y 573..1500，
    #   第 70 轮的夹取把译文整体下推到 573 之后，同时底板被同一轮廓裁掉上半截。）
    # 第 73 轮上调：+100 行 = 修「列首几个字被 det 切掉、整列不送翻译也不盖底板」
    #   （extendHeads()：竖排列首是助词/接续时把框顶向上长 3 字宽重读，只有「更长 + conf≥0.5 +
    #   旧读仍是结尾」才采用，每页最多 2 列；whiteStart() 把长过的框顶收回到气球白底起始行，
    #   免得底板糊到画面上；tailMatch() 算尾巴吻合度。copin 第 33 页「……息」就靠这条补回来。）
    "kt_files": 58,          # app/src/main/java 下的 Kotlin 文件数
    "kt_lines": 19300,      # 总行数（当前 19261）
    "max_file_lines": 2850, # 单文件行数（ReaderScreen.kt 2844，已知最大块，不许再长）
    "dead_strings": 0,       # UiStrings 里零引用的文案常量
    "unused_imports": 0,      # 确认无用的 import
    "experiment_marks": 0,   # 代码里的实验/兜底/TODO 标记
    "second_pipeline": 0,    # 第二套渲染路径 / 实验开关残留
}

# 这些 import 名字即使正文搜不到也必须保留
IMPORT_WHITELIST = {
    "getValue", "setValue", "R", "BuildConfig",
    "Composable", "Preview", "ExperimentalMaterial3Api",
}

# 注意：TODO 必须带词边界，否则 `toDouble()` 会被误判成 "toDo"。
EXPERIMENT_PAT = re.compile(
    r"实验|兜底|废弃|已弃|COVER_ORIGINAL|includeText|\bTODO\b|\bFIXME\b|"
    r"\btemporary\b|\bworkaround\b|\bhack\b|\bfor now\b|\bremove later\b",
    re.I,
)


def kt_files() -> list[Path]:
    return sorted(SRC.rglob("*.kt"))


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


def dead_ui_strings(files: list[Path]) -> list[str]:
    """UiStrings 里定义了、但全项目（含本文件内部）都没提过的常量/函数。

    本文件内部引用必须算进来：readModeOptions 数组引用 modePage/modeScroll/…
    、formatSize 引用 gb/mb/unknownSize，只看别的文件会把它们误判成死代码。
    """
    if not UISTRINGS.exists():
        return []
    own = read(UISTRINGS)
    others = "\n".join(read(f) for f in files if f != UISTRINGS)
    lines = own.splitlines()
    dead = []
    for m in re.finditer(
        r"^\s*(?:const\s+)?(?:val|fun)\s+([A-Za-z_][A-Za-z0-9_]*)\s*[=(]", own, re.M
    ):
        name = m.group(1)
        line_no = own[:m.start()].count("\n")
        used_inside = any(
            i != line_no and re.search(r"\b" + re.escape(name) + r"\b", line)
            for i, line in enumerate(lines)
        )
        used_outside = bool(re.search(r"\b" + re.escape(name) + r"\b", others))
        if not used_inside and not used_outside:
            dead.append(name)
    return dead


def unused_imports(files: list[Path]) -> list[tuple[str, str]]:
    """正文里完全搜不到名字的 import（白名单除外）。"""
    out = []
    for f in files:
        s = read(f)
        body = "\n".join(l for l in s.splitlines() if not l.strip().startswith("import "))
        for m in re.finditer(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", s, re.M):
            full, alias = m.group(1), m.group(2)
            simple = alias or full.split(".")[-1]
            if simple in IMPORT_WHITELIST or simple.endswith("*"):
                continue
            if not re.search(r"\b" + re.escape(simple) + r"\b", body):
                out.append((str(f.relative_to(ROOT)), full))
    return out


def experiment_marks(files: list[Path]) -> list[str]:
    out = []
    for f in files:
        for i, line in enumerate(read(f).splitlines(), 1):
            if EXPERIMENT_PAT.search(line):
                out.append("%s:%d %s" % (f.name, i, line.strip()[:90]))
    return out


def second_pipeline(files: list[Path]) -> list[str]:
    out = []
    render_dir = SRC / "com" / "yingcolor" / "lumanga" / "render"
    if render_dir.exists() and any(render_dir.rglob("*.kt")):
        out.append("存在 render/ 包 —— 第二套渲染路径又回来了")
    for f in files:
        s = read(f)
        for key in ("referenceRenderer", "PageRenderer", "PageJob", "TextBlockMerger"):
            if key in s:
                out.append("%s 里出现 %s" % (f.name, key))
    return out


def main() -> int:
    files = kt_files()
    lines = sum(len(read(f).splitlines()) for f in files)
    biggest = max(((len(read(f).splitlines()), f.name) for f in files), default=(0, ""))

    dead = dead_ui_strings(files)
    unused = unused_imports(files)
    marks = experiment_marks(files)
    pipeline = second_pipeline(files)

    metrics = {
        "kt_files": len(files),
        "kt_lines": lines,
        "max_file_lines": biggest[0],
        "dead_strings": len(dead),
        "unused_imports": len(unused),
        "experiment_marks": len(marks),
        "second_pipeline": len(pipeline),
    }

    print("=" * 62)
    print("Lumanga 代码体检   （基线见 tools/code_health.py 的 BASELINE）")
    print("=" * 62)
    ok = True
    for key, limit in BASELINE.items():
        val = metrics[key]
        flag = "OK " if val <= limit else "超标"
        if val > limit:
            ok = False
        print("  %-20s %6d  / 上限 %6d   %s" % (key, val, limit, flag))
    print("-" * 62)
    print("  最大文件: %s (%d 行)" % (biggest[1], biggest[0]))

    for title, items in (
        ("零引用文案常量", dead),
        ("未使用的 import", ["%s  %s" % (a, b) for a, b in unused]),
        ("实验/兜底标记", marks),
        ("第二套渲染残留", pipeline),
    ):
        if items:
            print("\n[%s] %d 项：" % (title, len(items)))
            for it in items[:30]:
                print("   -", it)
            if len(items) > 30:
                print("   ... 还有 %d 项" % (len(items) - 30))

    if "--fix" in sys.argv and (dead or unused):
        # 只自动清两类绝对安全的东西：零引用文案、确认没用到的 import。
        s = read(UISTRINGS)
        for name in dead:
            s = re.sub(
                r"^[ \t]*(?:const\s+)?(?:val|fun)\s+" + re.escape(name) + r"\b[^\n]*\n",
                "", s, flags=re.M,
            )
        UISTRINGS.write_text(s, encoding="utf-8", newline="\n")
        for rel, imp in unused:
            p = ROOT / rel
            t = read(p).replace("import %s\n" % imp, "")
            p.write_text(t, encoding="utf-8", newline="\n")
        print("\n--fix 已清理 %d 条文案、%d 条 import；请重新编译并跑一次体检。" % (len(dead), len(unused)))
        return 0

    print("\n结论：" + ("PASS 干净，可以交付。" if ok else "FAIL 有超标项，本轮必须清掉再交付。"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
