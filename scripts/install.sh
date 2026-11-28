#!/usr/bin/env bash
# =============================================================================
# LITV 服务端 · 一键部署（1Panel / 任意有 Docker 的 Linux）
#
# 用法（在服务器 SSH 里执行，一条命令）：
#   curl -fsSL https://raw.githubusercontent.com/ybd156/litv/main/scripts/install.sh | bash
#
# 装的过程中会让你挑版本（直接回车 = 最新版）；也可以一次性指定：
#   curl -fsSL .../install.sh | bash -s -- --version 0.1.24
#   curl -fsSL .../install.sh | bash -s -- --port 8080 --dir /opt/litv --auto-upgrade daily
#
# 可选参数：
#   --version <tag>        指定版本；不填则交互挑选（非交互环境下用最新版）
#   --list                 只列出 GHCR 上可用的版本，不安装
#   --dir <路径>           安装目录（默认 /opt/litv）
#   --port <端口>          宿主机端口（默认 28027）
#   --localhost            只绑 127.0.0.1（交给 1Panel 反代）
#   --auto-upgrade <when>  装完顺手开自动升级：daily | weekly | off | 04:30
#   --yes                  全用默认值，不问问题
#
# 做的事：
#   1. 检查 docker 与 compose 插件
#   2. 建数据目录并把属主改成 10001（容器内运行用户）
#   3. 若没有 config.yaml，就生成一份，密码随机，并打印出来
#   4. 拉镜像、启动容器、等健康检查
#   5. 装一个 `litv` 管理命令（status/list/upgrade/auto/logs）
#
# 幂等：重复执行不会覆盖已有 config.yaml，也不会重新生成密码。
# =============================================================================
set -eEuo pipefail
# 任何一步失败都打印行号，避免「只输出一行就退回提示符」这种没头没尾的失败
trap 'printf "\033[31m✗ 第 %s 行失败（退出码 %s）。把上面最后几行贴回来即可定位。\033[0m\n" "$LINENO" "$?" >&2' ERR

DIR="/opt/litv"
PORT="28027"
VERSION=""
IMAGE="ghcr.io/ybd156/litv"
# 默认只绑本机：1Panel 用反代对外，端口不暴露到公网。
# 要直接对外访问就加 --public（绑 0.0.0.0）。
BIND="127.0.0.1"
AUTO_UPGRADE=""
YES=0
LIST_ONLY=0
CLI_URL="${LITV_CLI_URL:-https://raw.githubusercontent.com/ybd156/litv/main/scripts/litv}"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --list) LIST_ONLY=1; shift ;;
    --public) BIND="0.0.0.0"; shift ;;
    --localhost|--bind-local) BIND="127.0.0.1"; shift ;;
    --auto-upgrade) AUTO_UPGRADE="${2:-daily}"; shift 2 ;;
    --yes|-y) YES=1; shift ;;
    -h|--help)
      sed -n '2,30p' "$0" 2>/dev/null || echo "见 https://github.com/ybd156/litv/blob/main/docs/deploy/1panel.md"
      exit 0 ;;
    *) echo "未知参数：$1（支持 --dir/--port/--version/--list/--public/--auto-upgrade/--yes）" >&2; exit 2 ;;
  esac
done

say() { printf '\033[36m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 版本列表
# 匿名拉 GHCR 的 tag（包是 public，不需要 docker login）
fetch_tags() {
  local repo="${IMAGE#ghcr.io/}" token
  token="$(curl -fsSL "https://ghcr.io/token?scope=repository:${repo}:pull&service=ghcr.io" 2>/dev/null \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
  [ -n "$token" ] || return 1
  curl -fsSL -H "Authorization: Bearer $token" \
    "https://ghcr.io/v2/${repo}/tags/list" 2>/dev/null \
    | grep -o '"tags":\[[^]]*\]' | tr -d '"' \
    | sed 's/tags:\[//; s/\]//' | tr ',' '\n' \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true
}

# 版本号从新到旧
list_versions() { fetch_tags | sort -u -Vr; }

if [ "$LIST_ONLY" = "1" ]; then
  say "==> ${IMAGE} 可用版本（新的在前）"
  V="$(list_versions)"
  if [ -z "$V" ]; then
    die "取不到版本列表（检查到 ghcr.io 的网络）"
  fi
  i=0
  for t in $V; do
    i=$((i + 1))
    if [ "$i" -le 20 ]; then printf '  %s\n' "$t"; fi
  done
  exit 0
fi

# 没指定版本就交互挑一个（从 /dev/tty 读，兼容 curl | bash）
if [ -z "$VERSION" ] && [ "$YES" = "0" ] && [ -r /dev/tty ]; then
  VERS="$(list_versions || true)"
  if [ -n "$VERS" ]; then
    echo
    say "==> 可选版本（新的在前）"
    i=0
    for t in $VERS; do
      i=$((i + 1))
      [ "$i" -le 12 ] || break
      printf '  %2d) %s\n' "$i" "$t"
    done
    DEFAULT_VER="$(printf '%s\n' "$VERS" | head -n1)"
    printf '请选择版本 [1-%s，直接回车 = 最新 %s]: ' "$i" "$DEFAULT_VER" < /dev/tty > /dev/tty
    read -r CHOICE < /dev/tty || CHOICE=""
    case "$CHOICE" in
      "") VERSION="$DEFAULT_VER" ;;
      *[!0-9]*) VERSION="$CHOICE" ;;            # 直接敲了版本号
      *) VERSION="$(printf '%s\n' "$VERS" | sed -n "${CHOICE}p")" ;;
    esac
    [ -n "$VERSION" ] || VERSION="$DEFAULT_VER"
    say "==> 选择 $VERSION"
  else
    warn "取不到版本列表，按最新版安装"
  fi
fi

# 还是空（非交互 / 网络不通）→ 用 latest 标签
VERSION="${VERSION:-latest}"

command -v docker >/dev/null 2>&1 || die "没有 docker。1Panel 里先在「应用商店」装 Docker，或 apt install docker.io"
docker compose version >/dev/null 2>&1 || die "docker 有，但缺 compose 插件（docker compose version 失败）。1Panel 的 Docker 一般自带。"

say "==> 数据目录 $DIR/data"
mkdir -p "$DIR/data"
chown -R 10001:10001 "$DIR/data" 2>/dev/null || warn "改属主失败（非 root？）容器可能报「数据库打不开」"

CFG="$DIR/data/config.yaml"
GENERATED=0
if [ -f "$CFG" ]; then
  say "==> 已有 $CFG，保留不动"
  PASSWORD="(沿用你原来的密码)"
else
  # 随机密码：去掉容易看错的字符。
  # 注意：不要写成 `tr -dc ... </dev/urandom | head -c 16`——head 提前关管道会让 tr 收到
  # SIGPIPE，配合 `set -o pipefail` 直接把整个脚本静默退出（第一版就踩了这个坑）。
  # 这里要么用 openssl，要么用「生产者定量、消费者读满」的写法。
  if command -v openssl >/dev/null 2>&1; then
    PASSWORD="$(openssl rand -base64 32 | LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' | cut -c1-16)"
  else
    PASSWORD="$(head -c 4096 /dev/urandom | LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' | cut -c1-16)"
  fi
  [ -n "$PASSWORD" ] || PASSWORD="litv$(date +%s)"
  say "==> 生成 $CFG（随机管理密码）"
  cat > "$CFG" <<YAML
server:
  host: 0.0.0.0
  port: 28027
  # 1Panel 上没有飞牛网关，必须留空，否则会去 bind 一个不存在的 socket
  gateway_socket: ""
  # 留空 = 管理台直接挂根路径
  prefix: ""
  read_timeout: 30
  write_timeout: 0
  timezone: Asia/Shanghai
  trust_proxy: true
  trusted_proxies:
    - 127.0.0.1/32
    - ::1/128
    - 172.16.0.0/12
    - 10.0.0.0/8
    - 192.168.0.0/16

database:
  path: /app/data/litv.db

auth:
  # 留空则首次启动自动生成并写回；生成后不要再改，否则已下发的播放地址全部失效
  secret: ""
  admin_password: "$PASSWORD"
  admin_token_hours: 8
  admin_max_sessions: 16
  dev_mode: false

stream:
  cache_dir: /app/data/cache
  max_concurrent: 100
  buffer_size: 32768
  ring_size: 6291456
  user_agent: "LITV/1.0 (docker)"
  upstream_timeout: 15
  direct_enabled: true

transcode:
  enabled: false
  ffmpeg: /usr/bin/ffmpeg
  hwaccel: none
  preset: veryfast

epg:
  enabled: true
  refresh_cron: "0 3 * * *"
  keep_days: 7
  default_urls: []
  max_mb: 200
  max_programmes: 500000

probe:
  enabled: true
  interval_minutes: 30
  timeout_seconds: 8
  concurrency: 4

timeshift:
  enabled: true
  dir: /app/data/timeshift
  segment_mb: 64
  max_segments: 12

remux:
  enabled: true
  ffmpeg: ""
  dir: /app/data/remux
  segment_seconds: 2
  playlist_size: 30
  idle_timeout_seconds: 60

record:
  enabled: true
  dir: /app/data/recordings

alert:
  enabled: false
  webhook_url: ""
  chat_id: ""
  min_interval_minutes: 30

dlna:
  enabled: false
  friendly_name: "LITV 直播"

log:
  level: info
  max_mb: 10
YAML
  GENERATED=1
  chown 10001:10001 "$CFG"
  chmod 600 "$CFG"
fi

say "==> 写编排文件 $DIR/docker-compose.yml"
cat > "$DIR/docker-compose.yml" <<YAML
services:
  litv:
    image: $IMAGE:$VERSION
    container_name: litv
    restart: unless-stopped
    ports:
      - "$BIND:$PORT:28027"
    volumes:
      - $DIR/data:/app/data
    environment:
      TZ: Asia/Shanghai
    healthcheck:
      test: ["CMD", "curl", "-fsS", "-o", "/dev/null", "http://127.0.0.1:28027/api/v1/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 20s
YAML

# 给 litv 管理命令用的状态文件（端口/绑定/镜像仓库），别手工改
cat > "$DIR/.litv.conf" <<CONF
# 由 install.sh 生成，litv 命令读取它
LITV_DIR=$DIR
LITV_PORT=$PORT
LITV_BIND=$BIND
LITV_IMAGE=$IMAGE
CONF

say "==> 装管理命令 litv"
if curl -fsSL "$CLI_URL" -o "$DIR/litv" 2>/dev/null && [ -s "$DIR/litv" ]; then
  chmod +x "$DIR/litv"
  if [ -w /usr/local/bin ] || [ "$(id -u)" = "0" ]; then
    ln -sf "$DIR/litv" /usr/local/bin/litv 2>/dev/null && say "    已链到 /usr/local/bin/litv"
  else
    warn "    没权限写 /usr/local/bin，用 $DIR/litv 调用（或手工加 PATH）"
  fi
else
  warn "    下载 litv 命令失败（$CLI_URL），稍后可手动补：curl -fsSL $CLI_URL -o $DIR/litv && chmod +x $DIR/litv"
fi

say "==> 拉镜像 $IMAGE:$VERSION"
docker compose -f "$DIR/docker-compose.yml" pull

say "==> 启动"
docker compose -f "$DIR/docker-compose.yml" up -d

say "==> 等健康检查（最多 90 秒）"
ok=0
for i in $(seq 1 30); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/api/v1/health" 2>/dev/null; then ok=1; break; fi
  sleep 3
done

echo
if [ "$ok" = "1" ]; then
  say "✅ 起来了"
else
  warn "⚠️  健康检查没通过，先看日志："
  echo "   docker compose -f $DIR/docker-compose.yml logs --tail 50"
fi

# 顺手开自动升级
if [ -n "$AUTO_UPGRADE" ]; then
  AT="04:30"
  case "$AUTO_UPGRADE" in
    daily|on|1) AT="04:30" ;;
    weekly) AT="04:30" ;;
    off|0|no) AT="" ;;
    [0-9]*:[0-9]*) AT="$AUTO_UPGRADE" ;;
    *) AT="04:30" ;;
  esac
  if [ -x "$DIR/litv" ]; then
    if [ -n "$AT" ]; then
      say "==> 开自动升级（每天 $AT）"
      "$DIR/litv" auto on "$AT" || warn "自动升级没开成，可稍后手工：litv auto on"
    else
      say "==> 关闭自动升级"
      "$DIR/litv" auto off || true
    fi
  fi
fi

echo
echo "  管理台：   http://127.0.0.1:$PORT/   （默认只绑本机，用 1Panel 反代对外）"
if [ "$BIND" = "0.0.0.0" ]; then
  echo "            （已绑 0.0.0.0，同网段可直接访问 http://<服务器IP>:$PORT/）"
fi
echo "  安装目录： $DIR"
echo "  管理密码： $PASSWORD"
if [ "$GENERATED" = "1" ]; then
  echo "            （已写进 $CFG，可自行修改后 litv restart）"
fi
echo
echo "下一步："
echo "  1) 1Panel → 网站 → 反向代理到 http://127.0.0.1:$PORT"
echo "  2) 反代配置里补：proxy_read_timeout 3600s;  proxy_buffering off;"
echo "  3) 登录管理台，导入你自己的源（本项目不内置任何直播源）"
echo
echo "日常运维（装好了一个 litv 命令）："
echo "  litv status            看容器与健康状态"
echo "  litv list              列出可用版本（* 标当前）"
echo "  litv upgrade           升到最新版（起不来会自动回滚）"
echo "  litv upgrade 0.1.24    指定版本 / 回滚"
echo "  litv auto on           打开每天自动升级（off 关闭，status 查看）"
echo "  litv logs -n 200       看日志"
