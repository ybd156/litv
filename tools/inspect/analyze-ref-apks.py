"""分析三个参考 APK 的频道页 / 节目单 / 播放页设计。

只提取设计信息（布局名、控件结构、交互文案），不改动 APK。
用 aapt2 解二进制 XML —— 它能把 res/layout/*.xml 还原成可读的树。
"""
import os
import re
import shutil
import subprocess
import sys

def _build_tools():
    """找 aapt2：优先 $ANDROID_HOME，其次 PATH。不写死任何本机路径。"""
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        bt = os.path.join(home, "build-tools")
        if os.path.isdir(bt):
            for ver in sorted(os.listdir(bt), reverse=True):
                cand = os.path.join(bt, ver, "aapt2")
                if os.path.exists(cand) or os.path.exists(cand + ".exe"):
                    return os.path.dirname(cand)
    found = shutil.which("aapt2")
    if found:
        return os.path.dirname(found)
    sys.exit("找不到 aapt2：设置 ANDROID_HOME，或把 build-tools 加到 PATH")


BT = _build_tools()
AAPT2 = os.path.join(BT, "aapt2")


# APK 路径从命令行传入，不写死本机目录。
# 用法：python3 tools/inspect/analyze-ref-apks.py 参考1.apk 参考2.apk ...
if len(sys.argv) < 2:
    sys.exit("用法：python3 %s <参考APK> [更多APK...]" % sys.argv[0])

APKS = [(os.path.splitext(os.path.basename(p))[0], p) for p in sys.argv[1:]]

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
