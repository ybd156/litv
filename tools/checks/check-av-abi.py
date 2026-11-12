#!/usr/bin/env python3
"""
检查新编的 ffmpeg 库和仓库里已有的 libmpv.so 是否 ABI 兼容。

为什么需要这个检查
------------------
仓库里的 libmpv.so 是用**某个特定版本的 ffmpeg** 编出来的（实测是 git 快照
N-126627，不是任何正式 tag）。它通过 DT_NEEDED 依赖 libavformat/libavcodec/
libavutil 等，并直接引用这些库导出的符号。

如果用另一个版本的 ffmpeg 重编这些库（比如为了加 OpenSSL），只要有一个
libmpv 引用的符号在新库里没有，dlopen 就会失败：

    UnsatisfiedLinkError: dlopen failed: cannot locate symbol ...

表现为「mpv 内核整个不可用」，而且是运行时才炸，编译阶段看不出来。

已经踩过两次：
  1. n7.1 / n8.0 都缺 av_dynamic_hdr_smpte2094_app5_* 两个符号
  2. 用 nm / objdump 解析这些 .so 会得到空列表，门禁静默失效，
     给出假的「兼容」结论——所以这里直接自己解析 ELF，不依赖外部工具。

用法
----
    check-av-abi.py <新库目录> <旧库目录>

旧库目录里要有 libmpv.so 和旧的 libav*.so 用于对比。
退出码 0 = 兼容；1 = 不兼容或无法判断。
"""
import struct
import sys
import os

LIBS = ["libavformat.so", "libavcodec.so", "libavutil.so",
        "libswscale.so", "libswresample.so"]

# 符号表读出来少于这个数就认为解析失败，不能当成"兼容"
MIN_SYMBOLS = 10


def _sections(d):
    is64 = d[4] == 2
    if is64:
        e_shoff, = struct.unpack_from("<Q", d, 0x28)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", d, 0x3A)
    else:
        e_shoff, = struct.unpack_from("<I", d, 0x20)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", d, 0x2E)
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if is64:
            secs.append(struct.unpack_from("<IIQQQQIIQQ", d, off))
        else:
            secs.append(struct.unpack_from("<IIIIIIIIII", d, off))
    return d, is64, secs, e_shstrndx


def dynamic_symbols(path, want_defined):
    """返回 .dynsym 里已定义（导出）或未定义（引用）的全局符号集合。"""
    d = open(path, "rb").read()
    d, is64, secs, shstrndx = _sections(d)
    if not secs:
        return None
    shstr = secs[shstrndx]

    def sec_name(x):
        e = d.index(b"\0", shstr[4] + x)
        return d[shstr[4] + x:e].decode(errors="replace")

    out = set()
    for s in secs:
        if sec_name(s[0]) != ".dynsym":
            continue
        strtab_sec = secs[s[6]]
        strtab = d[strtab_sec[4]:strtab_sec[4] + strtab_sec[5]]
        entsize = s[9] or (24 if is64 else 16)
        count = s[5] // entsize
        for i in range(count):
            off = s[4] + i * entsize
            if is64:
                nameoff, info, _other, shndx, _val, _sz = struct.unpack_from(
                    "<IBBHQQ", d, off)
            else:
                nameoff, _val, _sz, info, _other, shndx = struct.unpack_from(
                    "<IIIBBH", d, off)
            is_undef = (shndx == 0)
            if is_undef == want_defined:
                continue
            # 只要 GLOBAL(1) / WEAK(2)
            if (info >> 4) not in (1, 2):
                continue
            e = strtab.index(b"\0", nameoff)
            out.add(strtab[nameoff:e].decode(errors="replace"))
    return out


def main():
    if len(sys.argv) != 3:
        print("用法: check-av-abi.py <新库目录> <旧库目录>")
        return 2
    new_dir, old_dir = sys.argv[1], sys.argv[2]

    mpv = os.path.join(old_dir, "libmpv.so")
    if not os.path.isfile(mpv):
        print("找不到 %s" % mpv)
        return 1

    mpv_undef = dynamic_symbols(mpv, want_defined=False)
    if not mpv_undef or len(mpv_undef) < MIN_SYMBOLS:
        print("解析 libmpv.so 的符号表失败（%d 个），无法判断，按不兼容处理"
              % (len(mpv_undef) if mpv_undef else 0))
        return 1
    print("libmpv.so 引用外部符号 %d 个" % len(mpv_undef))

    fatal_total = 0
    for lib in LIBS:
        old_lib = os.path.join(old_dir, lib)
        new_lib = os.path.join(new_dir, lib)
        if not os.path.isfile(old_lib):
            print("  [跳过] 旧库没有 %s" % lib)
            continue
        if not os.path.isfile(new_lib):
            print("  [缺失] 新库没有 %s" % lib)
            fatal_total += 1
            continue

        old_syms = dynamic_symbols(old_lib, want_defined=True)
        new_syms = dynamic_symbols(new_lib, want_defined=True)
        if not old_syms or not new_syms or \
                len(old_syms) < MIN_SYMBOLS or len(new_syms) < MIN_SYMBOLS:
            print("  [错误] %s 符号表解析失败（旧 %d / 新 %d），按不兼容处理"
                  % (lib, len(old_syms or []), len(new_syms or [])))
            fatal_total += 1
            continue

        gone = old_syms - new_syms
        fatal = gone & mpv_undef
        if fatal:
            print("  [致命] %s 少了 %d 个 libmpv 需要的符号（该库共少 %d 个）："
                  % (lib, len(fatal), len(gone)))
            for s in sorted(fatal)[:10]:
                print("           %s" % s)
            fatal_total += len(fatal)
        else:
            print("  [ok] %s 兼容（导出 %d 个，旧有新无 %d 个但 libmpv 都不引用）"
                  % (lib, len(new_syms), len(gone)))

    print()
    if fatal_total:
        print("结论：不兼容，共 %d 处问题，不要发布这套 .so" % fatal_total)
        return 1
    print("结论：兼容")
    return 0


if __name__ == "__main__":
    sys.exit(main())
