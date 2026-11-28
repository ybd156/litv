#!/usr/bin/env bash
# =============================================================================
# LITV 服务端 · 一键部署（1Panel / 任意有 Docker 的 Linux）
#
# 用法（在服务器 SSH 里执行，一条命令）：
#   curl -fsSL https://raw.githubusercontent.com/ybd156/litv/main/scripts/install.sh | bash
#
# 可选参数（透传用 bash -s -- ）：
#   curl -fsSL .../install.sh | bash -s -- --port 8080 --dir /opt/litv --version 0.1.24
#
# 做的事：
#   1. 检查 docker 与 compose 插件
#   2. 建数据目录并把属主改成 10001（容器内运行用户）
#   3. 若没有 config.yaml，就生成一份，密码随机，并打印出来
#   4. 拉镜像、启动容器、等健康检查
#
# 幂等：重复执行不会覆盖已有 config.yaml，也不会重新生成密码。
# =============================================================================
set -euo pipefail

DIR="/opt/litv"
PORT="28027"
VERSION="0.1.24"
IMAGE="ghcr.io/ybd156/litv"
BIND="0.0.0.0"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --localhost) BIND="127.0.0.1"; shift ;;
    -h|--help)
      sed -n '2,20p' "$0" 2>/dev/null || echo "见 https://github.com/ybd156/litv/blob/main/docs/deploy/1panel.md"
      exit 0 ;;
    *) echo "未知参数：$1（支持 --dir/--port/--version/--localhost）" >&2; exit 2 ;;
  esac
done

say() { printf '\033[36m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

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
  # 随机密码：去掉容易看错的字符，长度 16
  PASSWORD="$(LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' </dev/urandom | head -c 16)"
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

echo "  管理台：   http://<服务器IP>:$PORT/"
if [ "$BIND" = "127.0.0.1" ]; then
  echo "            （已绑定 127.0.0.1，只能经 1Panel 反代访问）"
fi
echo "  管理密码： $PASSWORD"
if [ "$GENERATED" = "1" ]; then
  echo "            （已写进 $CFG，可自行修改后 docker compose ... restart litv）"
fi
echo
echo "下一步："
echo "  1) 1Panel → 网站 → 反向代理到 http://127.0.0.1:$PORT"
echo "  2) 反代配置里补：proxy_read_timeout 3600s;  proxy_buffering off;"
echo "  3) 登录管理台，导入你自己的源（本项目不内置任何直播源）"
echo
echo "升级：把 $DIR/docker-compose.yml 里的版本号改掉，再 docker compose -f $DIR/docker-compose.yml up -d"
