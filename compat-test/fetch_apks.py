#!/usr/bin/env python3
"""自动拉取不同版本的 Gboard APK 到 apks/<版本>/。

按优先级尝试多个来源（网络受限环境下部分来源可能失败，重复运行即可）:
  1. adb 已连接设备: 直接 pull 已安装的 Gboard（当前用户正在用的版本）
  2. GitHub release 存档仓库（可达性最好）
  3. APKPure 直链（需要网络可达 d.apkpure.com；默认关闭，用 --apkpure 开启）

用法:
    python fetch_apks.py            # 全部来源
    python fetch_apks.py --apkpure  # 额外尝试 APKPure
"""
import argparse
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
APKS = HERE / "apks"
GBOARD_PKG = "com.google.android.inputmethod.latin"
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}


def fetch(url: str, dest: Path, timeout: int = 600) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=timeout) as r, open(dest, "wb") as f:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
        return dest.stat().st_size > 1_000_000
    except Exception as e:  # noqa: BLE001
        print(f"  FAIL {url}: {e}")
        if dest.exists():
            dest.unlink()
        return False


def from_adb() -> None:
    adb = None
    for cand in ("adb", r"D:\android-sdk\platform-tools\adb.exe"):
        try:
            subprocess.run([cand, "start-server"], capture_output=True, timeout=30)
            out = subprocess.run([cand, "devices"], capture_output=True, text=True, timeout=30).stdout
            if "device\t" in out:
                adb = cand
                break
        except Exception:
            continue
    if not adb:
        print("adb: 无已连接设备")
        return
    paths = subprocess.run(
        [adb, "shell", "pm", "path", GBOARD_PKG], capture_output=True, text=True
    ).stdout
    apk_path = next((l.strip()[8:] for l in paths.splitlines() if l.startswith("package:")), None)
    if not apk_path:
        print("adb: 设备上没有 Gboard")
        return
    ver = subprocess.run(
        [adb, "shell", "dumpsys", "package", GBOARD_PKG], capture_output=True, text=True
    ).stdout
    import re
    m = re.search(r"versionName=([\d.\-beta]+)", ver)
    name = m.group(1) if m else "device-current"
    dest = APKS / f"device-{name}" / "gboard-device.apk"
    print(f"adb: pull {apk_path} -> {dest}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([adb, "pull", apk_path, str(dest)], timeout=600)
    print("adb: done")


def from_github() -> None:
    """扫 GitHub 上已知的 Gboard APK 存档仓库 release。
    API 限流时回退到已知的直链。"""
    repos = ["SuperSaveGroup/GBoardAPK", "afasca/gboard-android"]
    found_urls = []
    for repo in repos:
        try:
            api = f"https://api.github.com/repos/{repo}/releases?per_page=100"
            req = urllib.request.Request(api, headers=UA)
            data = json.load(urllib.request.urlopen(req, timeout=30))
        except Exception as e:  # noqa: BLE001
            print(f"github {repo}: {e}")
            continue
        for rel in data if isinstance(data, list) else []:
            for a in rel.get("assets", []):
                if a["name"].lower().endswith(".apk") and "gboard" in a["name"].lower():
                    found_urls.append((rel["tag_name"], a["name"], a["browser_download_url"]))
    # API 不可用时的已知直链（重跑无需 API）
    found_urls += [
        ("16.6.3.855170483", "gboard-16.6.3-arm64.apk",
         "https://github.com/SuperSaveGroup/GBoardAPK/releases/download/"
         "16.6.3.855170483/Gboard.-.the.Google.Keyboard_16.6.3.855170483-release-arm64-v8a_APKPure.apk"),
    ]
    seen = set()
    for tag, name, url in found_urls:
        if url in seen:
            continue
        seen.add(url)
        dest = APKS / f"github-{tag}" / name
        if dest.exists():
            print(f"github: {tag} 已存在")
            continue
        print(f"github: 下载 {tag}/{name}")
        fetch(url, dest)


def from_apkpure() -> None:
    """APKPure 直链（最新版）。被墙时不可用。"""
    url = f"https://d.apkpure.com/b/APK/{GBOARD_PKG}?version=latest"
    dest = APKS / "apkpure-latest" / "gboard-latest.apk"
    if dest.exists():
        print("apkpure: 已存在")
        return
    print("apkpure: 下载最新版")
    fetch(url, dest)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apkpure", action="store_true", help="尝试 APKPure（可能被墙）")
    args = ap.parse_args()
    APKS.mkdir(parents=True, exist_ok=True)
    print("== adb 设备 ==")
    from_adb()
    print("== GitHub 存档 ==")
    from_github()
    if args.apkpure:
        print("== APKPure ==")
        from_apkpure()
    print("\n完成。运行 python scan.py 生成兼容性矩阵。")
