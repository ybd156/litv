#!/usr/bin/env bash
# =============================================================================
# 检查新编的 ffmpeg 库和仓库里已有的 libmpv.so 是否 ABI 兼容
#
# 为什么需要这个检查：
#   仓库里的 libmpv.so 是用**某个特定版本的 ffmpeg** 编出来的（实测是
#   git 快照 N-126627，不是任何正式 tag）。它通过 DT_NEEDED 依赖
#   libavformat/libavcodec/libavutil 等，并直接引用这些库导出的符号。
#
#   如果用另一个版本的 ffmpeg 重编这些库（比如为了加 OpenSSL），
#   只要有一个 libmpv 引用的符号在新库里没有，dlopen 就会失败：
#       UnsatisfiedLinkError: dlopen failed: cannot locate symbol ...
#   表现为「mpv 内核整个不可用」，而且是运行时才炸，CI 编译阶段看不出来。
#
#   已经踩过一次：ffmpeg n7.1 比 N-126627 少了
#   av_dynamic_hdr_smpte2094_app5_alloc / _from_t35，
#   直接换会让 libmpv 加载失败。
#
# 用法：
#   OLD_DIR=<旧库目录> ./check-av-abi.sh <新库目录> <libmpv.so>
#
# 退出码：0 = 兼容；1 = 有缺失符号（不要发布）
# =============================================================================
set -uo pipefail

NEW_DIR="${1:?用法: check-av-abi.sh <新库目录> <libmpv.so>}"
MPV="${2:?用法: check-av-abi.sh <新库目录> <libmpv.so>}"

[ -d "$NEW_DIR" ] || { echo "目录不存在: $NEW_DIR"; exit 1; }
[ -f "$MPV" ]     || { echo "找不到 libmpv.so: $MPV"; exit 1; }
command -v objdump >/dev/null || { echo "缺少 objdump（binutils）"; exit 1; }

LIBS="libavformat.so libavcodec.so libavutil.so libswscale.so libswresample.so"

tmp_undef="$(mktemp)"
# 用 objdump -T 而不是 nm：实测 nm 在 NDK 编出来并 strip 过的 .so 上
# 会返回空（门禁因此静默失效过一次，给出了假的"兼容"结论）。
# objdump -T 的输出格式稳定：第 4 列是 *UND* 表示未定义。
objdump -T "$MPV" 2>/dev/null | awk '$4=="*UND*" && $NF!="" {print $NF}' | sort -u > "$tmp_undef"
echo "libmpv.so 引用外部符号 $(wc -l < "$tmp_undef") 个"

OLD_DIR="${OLD_DIR:-}"
if [ -z "$OLD_DIR" ] || [ ! -d "$OLD_DIR" ]; then
  echo "未提供 OLD_DIR，跳过对比（只做基本可用性检查）"
  for lib in $LIBS; do
    [ -f "$NEW_DIR/$lib" ] && echo "  [ok] 存在 $lib" || echo "  [缺失] $lib"
  done
  rm -f "$tmp_undef"
  exit 0
fi

missing_total=0
for lib in $LIBS; do
  old_lib="$OLD_DIR/$lib"; new_lib="$NEW_DIR/$lib"
  [ -f "$old_lib" ] || { echo "  [跳过] 旧库没有 $lib"; continue; }
  [ -f "$new_lib" ] || { echo "  [缺失] 新库没有 $lib"; missing_total=$((missing_total+1)); continue; }

  tmp_o="$(mktemp)"; tmp_n="$(mktemp)"
  objdump -T "$old_lib" 2>/dev/null | awk '$4!="*UND*" && $NF!="" {print $NF}' | sort -u > "$tmp_o"
  objdump -T "$new_lib" 2>/dev/null | awk '$4!="*UND*" && $NF!="" {print $NF}' | sort -u > "$tmp_n"

  # 防呆：符号表读不出来时绝不能当成"兼容"。
  # 之前用 nm 遇到过输出为空 -> comm 把旧库全部符号当成"新库没有"
  # -> 又因为 libmpv 引用的子集判断写错，最后得出"兼容"的假结论。
  o_cnt="$(grep -c . "$tmp_o" || true)"; n_cnt="$(grep -c . "$tmp_n" || true)"
  if [ "${o_cnt:-0}" -lt 10 ] || [ "${n_cnt:-0}" -lt 10 ]; then
    echo "  [错误] $lib 符号表读不出来（旧 ${o_cnt:-0} 个 / 新 ${n_cnt:-0} 个），无法判断，按不兼容处理"
    missing_total=$((missing_total + 1))
    rm -f "$tmp_o" "$tmp_n" "$tmp_o.gone" "$tmp_o.fatal"
    continue
  fi

  # 旧库导出、新库没有的符号
  comm -23 "$tmp_o" "$tmp_n" > "$tmp_o.gone"
  # 其中 libmpv 确实引用的，才是致命的
  comm -12 "$tmp_o.gone" "$tmp_undef" > "$tmp_o.fatal"
  # 调试：确认关键的 HDR 符号确实被检查到了
  if grep -q "av_dynamic_hdr_smpte2094_app5" "$tmp_o.gone" 2>/dev/null; then
    echo "       （检测到 av_dynamic_hdr_smpte2094_app5_* 差异）"
  fi
  n="$(grep -c . "$tmp_o.fatal" || true)"

  gone_n="$(grep -c . "$tmp_o.gone" || true)"
  if [ "${n:-0}" -gt 0 ]; then
    echo "  [致命] $lib 少了 $n 个 libmpv 需要的符号（该库共少 ${gone_n:-0} 个）："
    head -10 "$tmp_o.fatal" | sed 's/^/           /'
    missing_total=$((missing_total + n))
  else
    echo "  [ok] $lib 兼容（旧有新无的符号 ${gone_n:-0} 个，但 libmpv 都不引用）"
  fi
  rm -f "$tmp_o" "$tmp_n" "$tmp_o.gone" "$tmp_o.fatal"
done
rm -f "$tmp_undef"

if [ "$missing_total" -gt 0 ]; then
  echo
  echo "结论：不兼容，缺少 $missing_total 个 libmpv 需要的符号，不要发布这套 .so"
  exit 1
fi
echo
echo "结论：兼容"
