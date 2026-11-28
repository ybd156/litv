#!/usr/bin/env bash
# =============================================================================
# LITV 服务端 · 卸载（1Panel / 任意用 install.sh 装的环境）
#
# 用法：
#   curl -fsSL https://raw.githubusercontent.com/ybd156/litv/main/scripts/uninstall.sh | bash
#   # 想连数据一起删：
#   curl -fsSL .../uninstall.sh | bash -s -- --purge
#
# 参数：
#   --dir <路径>     安装目录（默认读 <目录>/.litv.conf，再不行用 /opt/litv）
#   --purge          连 /opt/litv/data（数据库 + 配置 + 录播 + 时移缓存）一起删
#   --keep-image     保留本地镜像（默认顺手删掉，省空间）
#   --yes            不问，直接执行
#
# 默认行为（不带 --purge）：
#   停容器 → 删容器/网络 → 关自动升级 → 删 litv 命令 → 保留数据目录并告诉你路径。
#   也就是「卸载程序、留住数据」，重装后把 data/ 放回去就能恢复。
#
# 安全护栏：只删「看起来确实是 LITV 安装目录」的路径——目录里必须有
# docker-compose.yml 且提到 ghcr.io/ybd156/litv；拒绝 /、/opt、/usr 这类路径。
# =============================================================================
set -eEuo pipefail
trap 'printf "\033[31m✗ 第 %s 行失败（退出码 %s）。把上面最后几行贴回来即可定位。\033[0m\n" "$LINENO" "$?" >&2' ERR

DIR=""
PURGE=0
KEEP_IMAGE=0
YES=0
UNIT_NAME="litv-upgrade"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --purge) PURGE=1; shift ;;
    --keep-image) KEEP_IMAGE=1; shift ;;
    --yes|-y) YES=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（支持 --dir/--purge/--keep-image/--yes）" >&2; exit 2 ;;
  esac
done

say()  { printf '\033[36m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 定位安装目录
if [ -z "$DIR" ]; then
  if [ -f /opt/litv/.litv.conf ]; then
    DIR="/opt/litv"
  elif [ -f ./docker-compose.yml ] && grep -q 'ghcr.io/ybd156/litv' ./docker-compose.yml; then
    DIR="$(pwd)"
  else
    DIR="/opt/litv"
  fi
fi

LITV_IMAGE="ghcr.io/ybd156/litv"
LITV_PORT="28027"
if [ -f "$DIR/.litv.conf" ]; then
  # shellcheck disable=SC1091
  . "$DIR/.litv.conf"
fi

COMPOSE="$DIR/docker-compose.yml"

# ---------------------------------------------------------------- 安全护栏
is_install_dir() {
  [ -f "$COMPOSE" ] && grep -q 'ghcr.io/ybd156/litv' "$COMPOSE"
}

refuse_dangerous() {
  case "$1" in
    ""|"/"|"/opt"|"/opt/"|"/usr"|"/usr/"|"/home"|"/root"|"/etc"|"/var"|"/tmp") return 0 ;;
  esac
  # 路径层级太浅（如 /data）也拒绝
  local depth; depth="$(printf '%s' "$1" | tr -cd '/' | wc -c)"
  [ "$depth" -lt 2 ] && return 0
  return 1
}

if refuse_dangerous "$DIR"; then
  die "拒绝操作：$DIR 不像一个具体的安装目录（层级太浅或属于系统目录）。请用 --dir 指定，例如 --dir /opt/litv"
fi

if [ ! -d "$DIR" ]; then
  warn "$DIR 不存在，可能已经卸载过了"
  if [ "$PURGE" = "1" ]; then die "没有可删的数据目录"; fi
  exit 0
fi

if ! is_install_dir && [ "$PURGE" = "1" ]; then
  die "$DIR 里没有 LITV 的 docker-compose.yml，不敢按 --purge 删数据。确认路径后用 --dir 再试。"
fi

# ---------------------------------------------------------------- 确认
VERSION="$(sed -n 's/^[[:space:]]*image:[[:space:]]*[^:]*:\(.*\)$/\1/p' "$COMPOSE" 2>/dev/null | head -n1 || true)"
say "==> 即将卸载"
echo "    安装目录： $DIR"
echo "    镜像版本： ${VERSION:-未知}"
if [ "$PURGE" = "1" ]; then
  echo "    数据目录： $DIR/data  —— ⚠️  会被删除（数据库、配置、录播、时移缓存）"
else
  echo "    数据目录： $DIR/data  —— 保留"
fi

if [ "$YES" != "1" ] && [ -r /dev/tty ]; then
  printf '继续？输入 yes 回车确认：' > /dev/tty
  read -r ANS < /dev/tty || ANS=""
  [ "$ANS" = "yes" ] || { echo "已取消"; exit 0; }
fi

# ---------------------------------------------------------------- 关自动升级
say "==> 关闭自动升级"
if command -v systemctl >/dev/null 2>&1; then
  systemctl disable --now "$UNIT_NAME.timer" >/dev/null 2>&1 || true
  rm -f "/etc/systemd/system/$UNIT_NAME.service" "/etc/systemd/system/$UNIT_NAME.timer"
  systemctl daemon-reload >/dev/null 2>&1 || true
fi
if command -v crontab >/dev/null 2>&1; then
  ( crontab -l 2>/dev/null | grep -v "$UNIT_NAME" ) | crontab - 2>/dev/null || true
fi

# ---------------------------------------------------------------- 停容器
if command -v docker >/dev/null 2>&1 && [ -f "$COMPOSE" ]; then
  say "==> 停止并删除容器"
  docker compose -f "$COMPOSE" down --remove-orphans || warn "docker compose down 失败，容器可能还在（docker ps 看一下）"
else
  warn "没有 docker 或没有编排文件，跳过容器清理"
fi

# ---------------------------------------------------------------- 删镜像
if [ "$KEEP_IMAGE" != "1" ] && [ -n "${VERSION:-}" ] && command -v docker >/dev/null 2>&1; then
  say "==> 删除镜像 $LITV_IMAGE:$VERSION"
  docker rmi "$LITV_IMAGE:$VERSION" >/dev/null 2>&1 || warn "镜像没删掉（可能在用或已不存在）"
fi

# ---------------------------------------------------------------- 删 litv 命令
if [ -L /usr/local/bin/litv ] || [ -f /usr/local/bin/litv ]; then
  say "==> 删除 /usr/local/bin/litv"
  rm -f /usr/local/bin/litv
fi

# ---------------------------------------------------------------- 数据与目录
if [ "$PURGE" = "1" ]; then
  say "==> 删除整个安装目录 $DIR"
  rm -rf -- "$DIR"
  echo
  printf '\033[32m%s\033[0m\n' "✅ 已卸载，数据也删了"
else
  # 只删程序部分，data/ 留着
  say "==> 删除编排与命令文件（保留 data/）"
  rm -f "$COMPOSE" "$DIR/.litv.conf" "$DIR/litv" "$DIR"/docker-compose.yml.bak.* 2>/dev/null || true
  if [ -x "$DIR/litv" ]; then rm -f "$DIR/litv"; fi
  echo
  printf '\033[32m%s\033[0m\n' "✅ 已卸载，数据保留在 $DIR/data"
  echo
  echo "  重装：跑 install.sh 时指定同一个目录，例如"
  echo "    curl -fsSL https://raw.githubusercontent.com/ybd156/litv/main/scripts/install.sh | bash -s -- --dir $DIR"
  echo "  彻底删除（不可恢复）："
  echo "    rm -rf -- $DIR"
fi

echo
echo "如果 1Panel 里建过反代，记得去「网站 → 反向代理」把那条也删掉（脚本不动面板数据）。"
