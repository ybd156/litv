#!/usr/bin/env python3
"""校验 libplayer.so 与同目录的 libav*/libmpv 是"同一代"编出来的。

为什么需要这一条
----------------
`libplayer.so` 是 is.xyz.mpv 的 JNI 桥，它和 ffmpeg 强耦合。2026-11-24 的真机事故：

    dlopen failed: cannot locate symbol "av_jni_set_java_vm" referenced by libplayer.so

查完 ELF 才发现：libavcodec 已经**定义**了 av_jni_set_java_vm，但旧 libplayer.so 还引用
`av_jni_set_android_app_ctx`（ffmpeg 7/8 才有）。也就是说它和同目录的 ffmpeg 不是同一代。
这种不一致只有在**运行时 dlopen** 才会炸，CI 全程绿 —— 所以必须有这条静态门禁。

判据（三条，全部基于 .dynsym，与仓库 check-av-abi.py 共用同一个解析器）：
  1. libplayer.so 必须导出 JNI 入口 Java_is_xyz_mpv_MPVLib_create；
  2. libplayer.so 引用的每个 `av_jni_*` 符号，必须在同目录某个 lib 里**被定义**；
  3. 反过来也查一遍 libmpv.so 的 `av_jni_*` 引用。

用法：python3 tools/checks/check-player-jni.py <包含 libplayer.so 的目录>
退出码：0 合格 / 1 不合格 / 2 用法或解析错误
"""

from __future__ import annotations

import glob
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load_parser():
    spec = importlib.util.spec_from_file_location("check_av_abi", os.path.join(HERE, "check-av-abi.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    if len(sys.argv) != 2:
        print("用法: check-player-jni.py <dir-with-libs>")
        return 2
    d = sys.argv[1]
    if not os.path.isdir(d):
        print(f"❌ 目录不存在: {d}")
        return 2

    cav = load_parser()

    def syms(path, defined):
        s = cav.dynamic_symbols(path, defined)
        return set(s) if s else set()

    libs = sorted(glob.glob(os.path.join(d, "*.so")))
    if not libs:
        print(f"❌ {d} 里没有 .so")
        return 1

    defined_av = set()
    for p in libs:
        if os.path.basename(p).startswith("libav"):
            defined_av |= syms(p, True)

    player = os.path.join(d, "libplayer.so")
    mpv = os.path.join(d, "libmpv.so")
    if not os.path.exists(player):
        print(f"❌ 缺 libplayer.so（{d}）")
        return 1

    fails = []

    # 1. JNI 入口
    entry = "Java_is_xyz_mpv_MPVLib_create"
    if entry not in syms(player, True):
        fails.append(f"libplayer.so 没有导出 JNI 入口 {entry}")

    # 2/3. av_jni_* 引用必须被同目录的 libav* 定义
    for name, path in (("libplayer.so", player), ("libmpv.so", mpv)):
        if not os.path.exists(path):
            continue
        undef = syms(path, False)
        need = {s for s in undef if s.startswith("av_jni_")}
        missing = need - defined_av
        if missing:
            fails.append(
                f"{name} 引用的 {sorted(missing)} 在同目录 libav* 里没有定义"
                "（说明 JNI 桥与 ffmpeg 不是同一代编的）"
            )

    if fails:
        print("❌ libplayer 与 ffmpeg 不同代：")
        for f in fails:
            print("   -", f)
        return 1

    print(f"✅ libplayer/libmpv 的 av_jni_* 引用全部由同目录 libav* 提供（{os.path.basename(d.rstrip('/\\\\'))}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
