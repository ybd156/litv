"""分析三个参考 APK 的频道页 / 节目单 / 播放页设计。

只提取设计信息（布局名、控件结构、交互文案），不改动 APK。
用 aapt2 解二进制 XML —— 它能把 res/layout/*.xml 还原成可读的树。
"""
import os
import re
import subprocess
import sys

BT = os.path.expanduser("~/android-dev/sdk/build-tools/35.0.0")
AAPT2 = os.path.join(BT, "aapt2")

APKS = [
    ("DIYP", "/mnt/d/Downloads/配套_DIYP.apk"),
    ("酷9", "/mnt/d/Downloads/酷9原版32位2.2.21_20261018_2014.apk"),
    ("mediaplayer", "/mnt/d/Downloads/mediaplayer-v1.1.25-armeabi-v7a-release (2).apk"),
]

# 关注这些关键词的布局
KEY = re.compile(r"(channel|epg|live|program|list|grid|player|play|home|main|item|guide|timeline)", re.I)


def run(args, timeout=120):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout).stdout
    except Exception as e:
        return f"__ERR__ {e}"


def analyze(name, path):
    print("=" * 70)
    print(f"### {name}")
    print("=" * 70)

    badging = run([AAPT2, "dump", "badging", path])
    for line in badging.split("\n"):
        if line.startswith(("package:", "launchable-activity:", "application-label:", "sdkVersion", "targetSdkVersion")):
            print(" ", line[:150])

    res = run([AAPT2, "dump", "resources", path], timeout=180)
    layouts = sorted(set(re.findall(r"layout/([A-Za-z0-9_]+)", res)))
    interesting = [l for l in layouts if KEY.search(l)]
    print(f"\n  布局总数 {len(layouts)}，相关的 {len(interesting)} 个：")
    for l in interesting[:40]:
        print("   -", l)

    strs = run([AAPT2, "dump", "strings", path], timeout=180)
    hits = []
    for kw in ["节目单", "频道", "回看", "时移", "线路", "收藏", "分组", "换台", "预约", "录制"]:
        if kw in strs:
            hits.append(kw)
    print("\n  出现的功能文案:", "、".join(hits) if hits else "(无)")
    return interesting


if __name__ == "__main__":
    for n, p in APKS:
        if not os.path.exists(p):
            print("缺失:", p)
            continue
        analyze(n, p)
