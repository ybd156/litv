# syntax=docker/dockerfile:1

# LITV 服务端镜像：管理台 → 内嵌静态资源 → 单个静态二进制。
#
# 与 packaging/fnos/build.sh 的做法一致：先把 admin/dist 放进 server/internal/web/www，
# 再由 go:embed 打进二进制。所以运行期不需要 nginx 托管前端，也不需要额外拷文件。
#
# 与 fnOS 版的唯一区别在"入口"：fnOS 靠统一网关的 Unix Socket 提供管理台身份，
# 这里没有网关，管理台走同一个 TCP 端口的密码登录（auth.admin_password）。

ARG NODE_IMAGE=node:22-bookworm-slim
ARG GO_IMAGE=golang:1.23-bookworm
ARG RUNTIME_IMAGE=debian:bookworm-slim

# ---------- 1. 管理台 ----------
FROM ${NODE_IMAGE} AS admin
WORKDIR /src/admin
COPY admin/package.json admin/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY admin/ ./
RUN npm run build

# ---------- 2. 服务端 ----------
FROM ${GO_IMAGE} AS build
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
# 仓库里那份 www 是历史构建产物，必须丢掉重来，否则内嵌的是旧管理台。
RUN rm -rf ./internal/web/www && mkdir -p ./internal/web/www
COPY --from=admin /src/admin/dist/ ./internal/web/www/
ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath \
      -ldflags "-s -w -X main.version=${VERSION}" \
      -o /out/litvd ./cmd/litvd

# ---------- 3. 运行时 ----------
FROM ${RUNTIME_IMAGE}
# ffmpeg 是运行期依赖：remux（裸 TS → HLS）、录制、时移都要调它。
# 少了它不会启动失败，而是等到播放/录制时才报错，所以必须装进镜像。
# curl 只给 healthcheck 用（镜像里没有它就没法探活）。
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin litv \
 && mkdir -p /app/data \
 && chown -R 10001:0 /app

WORKDIR /app
COPY --from=build /out/litvd /app/litvd

USER 10001
ENV TZ=Asia/Shanghai

# 28027 是 config.Default() 里的默认端口，同时提供管理台与终端 API。
EXPOSE 28027
# 数据库、缓存、录制、时移、config.yaml 全在这一个目录里。
VOLUME ["/app/data"]

ENTRYPOINT ["/app/litvd"]
CMD ["-config", "/app/data/config.yaml"]
