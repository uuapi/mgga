#!/usr/bin/env python3
"""MGGA Gboard 兼容性扫描器。

对若干 Gboard APK 做静态 DEX 扫描，验证 MGGA 各 hook 锚点在该版本是否存在。
不执行任何 APK 代码，仅解析 classes*.dex 内的字符串池（MUTF-8）。

用法:
    python scan.py [目录或 apk 路径 ...]
默认扫描 ./apks 下所有 .apk。输出兼容性矩阵到 stdout 和 compat-report.json。
"""
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

# ---------- 锚点定义 ----------
# kind=str: dex 字符串池中需出现该原文（DexKit usingStrings 的前置条件）
# kind=cls: dex 描述符 Lxxx/yyy; 需出现（findClassIfExists 的前置条件）
ANCHORS = [
    ("flag_reader", "str", "Invalid flag: ", "FlagEngine: DexKit 泛型 flag 读取器特征串"),
    ("flag_reader2", "str", "Invalid flag:", "FlagEngine: fallback 特征串"),
    ("retention", "str", "Failed to parse retention hours", "Clipboard: 保存时长解析方法特征串"),
    ("clipboard_provider", "cls", "Lcom/google/android/apps/inputmethod/libs/clipboard/ClipboardContentProvider;", "Clipboard: 剪贴板 Provider 类"),
    ("softkey_view", "cls", "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyView;", "通用: 键位视图（双拼/译词/九键键面）"),
    ("softkey_def", "cls", "Lcom/google/android/libraries/inputmethod/metadata/SoftKeyDef;", "通用: 键位元数据"),
    ("latin_ime", "cls", "Lcom/android/inputmethod/latin/LatinIME;", "Termux: IME 服务类"),
    ("number_mode_kb", "cls", "Lcom/google/android/apps/inputmethod/latin/keyboard/LatinKeyboardWithNumberMode;", "九键标点: 数字页键盘类"),
    ("autoscaletv", "cls", "Lcom/google/android/libraries/inputmethod/widgets/text/AutoCenterScaleTextView;", "Emoji 提亮: 候选文本视图"),
    ("commit_wrapper", "cls", "Laekp;", "九键标点: commitText 包装兜底类 (18.5.4)"),
    ("voice_aajc", "cls", "Laajc;", "语音: Google 结果回调类 (18.4.1)"),
    ("voice_kkw", "cls", "Lkkw;", "语音: 引擎回调类 (18.4.1)"),
    ("strip_holder", "cls", "Lcom/google/android/libraries/inputmethod/keyboard/widget/ReadingTextCandidateHolderView;", "九键标点: 标点栏容器"),
]

FLAG_CLASS_CANDIDATES = ["adbx", "odx", "obw", "acps", "nxw", "ajoh"]  # FuckGboard 各版本 flag 类


def read_dex_strings(dex: bytes):
    """解析 DEX header + string_ids，返回字符串集合。"""
    if len(dex) < 112 or dex[:4] != b"dex\n":
        return set()
    string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 56)
    out = set()
    for i in range(string_ids_size):
        (off,) = struct.unpack_from("<I", dex, string_ids_off + i * 4)
        # uleb128 长度
        result = 0
        shift = 0
        p = off
        while p < len(dex):
            b = dex[p]
            p += 1
            result |= (b & 0x7F) << shift
            if not (b & 0x80):
                break
            shift += 7
        end = dex.find(b"\x00", p)
        if end < 0:
            continue
        out.add(dex[p:end].decode("utf-8", "replace"))
    return out


def scan_apk(path: Path) -> dict:
    info = {"apk": str(path), "anchors": {}, "flag_classes": []}
    strings_total = set()
    try:
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
            for n in names:
                strings_total |= read_dex_strings(z.read(n))
        info["dex_files"] = len(names)
    except Exception as e:  # noqa: BLE001
        info["error"] = str(e)
        return info

    for name, kind, needle, desc in ANCHORS:
        if kind == "str":
            info["anchors"][name] = needle in strings_total
        else:
            info["anchors"][name] = needle in strings_total

    for cand in FLAG_CLASS_CANDIDATES:
        if f"L{cand};" in strings_total:
            info["flag_classes"].append(cand)
    return info


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    targets = sys.argv[1:] or ["apks"]
    apks = []
    for t in targets:
        p = Path(t)
        if p.is_dir():
            apks.extend(sorted(p.rglob("*.apk")))
        elif p.is_file():
            apks.append(p)
    if not apks:
        print("未找到 APK。把 Gboard apk 放进 compat-test/apks/<版本>/ 后重试。")
        return

    reports = []
    for apk in apks:
        print(f"\n=== {apk} ===")
        r = scan_apk(apk)
        reports.append(r)
        if "error" in r:
            print(f"  解析失败: {r['error']}")
            continue
        for name, _k, needle, desc in ANCHORS:
            mark = "✓" if r["anchors"][name] else "✗"
            print(f"  {mark} {name:20} {desc}")
        print(f"  flag 类候选命中: {r['flag_classes'] or '无'}")

    out = Path(__file__).parent / "compat-report.json"
    out.write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n报告已写入 {out}")


if __name__ == "__main__":
    main()
