#!/usr/bin/env python3
"""对比本次 scan 结果与 git 基线（HEAD 里的 compat-report.json）。

任一锚点从 ✓ 退化为 ✗（或 flag 类候选减少）即视为"漂移"：
  - 把漂移详情写入 compat-drift.md
  - 退出码 1（供 CI 触发开 issue）

用法: python diff_report.py [新版报告路径] [基线路径]
"""
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).parent
NEW = Path(sys.argv[1] if len(sys.argv) > 1 else HERE / "compat-report.json")
BASE = Path(sys.argv[2] if len(sys.argv) > 2 else HERE / "compat-report.json")


def baseline() -> list:
    """基线 = git HEAD 中的报告；仓库无基线时返回空。"""
    try:
        blob = subprocess.run(
            ["git", "show", f"HEAD:{BASE.relative_to(HERE.parent).as_posix()}"],
            capture_output=True, check=True,
        ).stdout
        return json.loads(blob)
    except Exception:  # noqa: BLE001
        return []


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not NEW.exists():
        print("no report"); return 2
    old = {(r.get("apk", "").split("/")[-1]): r for r in baseline()}
    new = json.loads(NEW.read_text(encoding="utf-8"))

    drifts = []
    for r in new:
        if "error" in r:
            continue
        key = Path(r["apk"]).name
        base = old.get(key) or old.get(r.get("apk", ""))
        if not base or "error" in base:
            continue
        for anchor, now in r["anchors"].items():
            was = base.get("anchors", {}).get(anchor)
            if was is True and now is False:
                drifts.append(f"- `{key}` 锚点 **{anchor}** 由 ✓ → ✗")
        bflag = set(base.get("flag_classes", []))
        nflag = set(r.get("flag_classes", []))
        lost = bflag - nflag
        if lost:
            drifts.append(f"- `{key}` flag 类候选减少: {sorted(lost)}")

    if not drifts:
        print("no drift")
        (HERE / "compat-drift.md").write_text("无锚点漂移。", encoding="utf-8")
        return 0

    body = "# ⚠️ Gboard 锚点漂移\n\n以下 hook 锚点在最新版本中消失，相关功能将自动降级：\n\n" + "\n".join(drifts)
    body += "\n\n> 由兼容性哨兵 workflow 自动生成；详情见 compat-report.json。\n"
    (HERE / "compat-drift.md").write_text(body, encoding="utf-8")
    print(body)
    return 1


if __name__ == "__main__":
    sys.exit(main())
