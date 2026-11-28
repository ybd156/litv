# 在 1Panel 上部署 LITV 服务端

LITV 服务端是**单个 Go 二进制**（管理台的静态文件通过 `go:embed` 打进这个二进制），
只依赖一个 SQLite 文件和一个数据目录，运行期额外需要 `ffmpeg`。
所以它在 1Panel 上的形态很简单：**一个容器 + 一个端口 + 一个数据目录**，前面挂 1Panel 的 OpenResty 做 HTTPS 反代。

仓库为此准备了四个文件：

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 多阶段构建：编管理台 → 放进 `server/internal/web/www` → 编 `litvd` → 运行时镜像带 ffmpeg |
| `.dockerignore` | 把 android/iOS/产物/运行时数据挡在构建上下文外 |
| `docker-compose.yml` | 单服务编排，映射 28027 与 `./data` |
| `config/config.example.yaml` | 容器版配置模板（路径都在 `/app/data` 下） |

> 与飞牛 fnOS 版的关键差异：fnOS 靠统一网关的 Unix Socket 提供管理台身份；
> 1Panel 没有网关，管理台走**同一个 TCP 端口的密码登录**，所以 `auth.admin_password` **必须设**，
> 而 `server.gateway_socket` **必须留空**。

---

## 1. 前置条件

- 1Panel ≥ 1.10（用到「编排」与「网站 → 反向代理」）
- 服务器已装 Docker（1Panel 安装时默认带）；**x86_64 或 arm64 都可**（镜像是多阶段构建，架构跟随宿主机）
- 磁盘：程序约 120 MB，数据按使用量（时移 + 录制最吃空间，见 §7）
- 可选：一个已解析到服务器的域名（做 HTTPS 用；纯内网用 IP 也能跑）

---

## 2. 上传代码，准备数据目录

```bash
# 放哪儿都行，示例用 /opt/litv
git clone <你的仓库地址> /opt/litv
cd /opt/litv

# 数据目录：数据库、缓存、录制、时移、config.yaml 全在这里
mkdir -p data
cp config/config.example.yaml data/config.yaml

# 必改：管理台密码；顺手确认 gateway_socket 是空串
vi data/config.yaml

# 容器内以 uid 10001 运行，宿主机目录要对得上，否则启动就报数据库不可写
chown -R 10001:10001 data
```

`data/config.yaml` 里至少确认三处：

```yaml
server:
  gateway_socket: ""        # 1Panel 没有飞牛网关，必须留空
  prefix: ""                # 留空 = 管理台在根路径 https://域名/
auth:
  admin_password: "你的强密码"
  dev_mode: false           # 千万别开：开了等于无鉴权管理员
```

> `prefix` 就是管理台的挂载路径。留空最省事（根路径，反代不用改写）；
> 想挂在子路径就填 `/litv`，那管理台是 `https://域名/litv/`，反代与客户端地址都要跟着带 `/litv`。

---

## 3. 启动容器

### 方式 0：一键脚本（最快，SSH 一条命令）

```bash
curl -fsSL https://raw.githubusercontent.com/ybd156/litv/main/scripts/install.sh | bash
```

它会：检查 docker/compose → 建 `/opt/litv/data` 并把属主改成 10001 → **生成一份带随机管理密码的
`config.yaml`**（已存在则不覆盖）→ 写编排 → 拉镜像 → 启动 → 等健康检查，最后把访问地址和密码打印出来。

可调参数（默认端口 28027、目录 `/opt/litv`、版本 0.1.24）：

```bash
curl -fsSL https://raw.githubusercontent.com/ybd156/litv/main/scripts/install.sh \
  | bash -s -- --port 8080 --dir /opt/litv --version 0.1.24 --localhost
```

`--localhost` 表示把端口只绑到 `127.0.0.1`（后面接 1Panel 反代时用，推荐）。
脚本是幂等的：重复执行不会覆盖已有配置和密码。

### 方式 A：直接拉 GitHub 上的镜像（推荐，服务器不用编译）

镜像由 `.github/workflows/docker-image.yml` 在 GitHub Actions 里构建并推到 `ghcr.io/ybd156/litv`
（推 `v*` tag 或手动跑一次该工作流就会出镜像，标签是版本号 + `latest`）。

1Panel → **应用** → **编排** → **创建编排**，编排内容用仓库里的 **`docker-compose.image.yml`**
（若你用的是自己的 fork，把里面的 `ghcr.io/ybd156/litv` 换成你自己的仓库地址）。

命令行等价写法：

```bash
cd /opt/litv
docker compose -f docker-compose.image.yml up -d
# 锁定版本（推荐，升级时手改这一处）：
LITV_VERSION=0.1.24 docker compose -f docker-compose.image.yml up -d
```

> 镜像目前是 **public**（匿名可拉，2026-11-28 实测），服务器不用登录。
> 若哪天改成 private：要么去 GitHub 仓库 → **Packages** → 该镜像 → **Package settings** 改回 public，
> 要么先在服务器上 `docker login ghcr.io`（token 勾 `read:packages`）。
>
> 只发布了 `linux/amd64`。ARM NAS（飞牛 ARM 机型）见文末「验证边界」。

### 方式 B：1Panel 编排，从源码构建（有完整构建机时用）

1Panel → **应用** → **编排** → **创建编排**：

- 名称：`litv`
- 源码目录：`/opt/litv`（即上面 clone 的位置）
- 编排内容：直接粘贴仓库里的 `docker-compose.yml`
- 点「确认」，首次会自动 `build` 出 `litv-server:0.1.24`

> 构建期间要装 npm 依赖并编译 Go，视机器性能约 3–8 分钟；1Panel 的编排日志里能看到进度。

### 方式 C：先在本机构建，再 `docker load` 到服务器（服务器性能弱、又不能拉 ghcr 时用）

```bash
# 在有 Docker 的机器上，仓库根目录
docker build -t litv-server:0.1.24 --build-arg VERSION=0.1.24 .
docker save litv-server:0.1.24 | gzip > litv-server.tar.gz
# 传到服务器后
gunzip -c litv-server.tar.gz | docker load
```

然后把 `docker-compose.yml` 里的 `build:` 段删掉，只留 `image:`，用 1Panel 编排启动。

### 方式 D：命令行从源码构建（排查用）

```bash
cd /opt/litv && docker compose up -d --build && docker compose logs -f
```

启动成功的标志（`docker compose logs` 里）：

```
TCP 入口已启动  addr=[::]:28027 version=0.1.24
```

---

## 4. 反向代理 + HTTPS

1Panel → **网站** → **创建网站** → 类型选 **反向代理**：

- 主域名：你的域名
- 代理地址：`http://127.0.0.1:28027`
- 点「确认」，再去「HTTPS」里申请证书并开启

代理路径保持 `/` 就行：默认配置里 `server.prefix` 是空串，服务端所有路由（管理台 + API + 流）都挂在根路径上，
管理台就是 `https://域名/`。如果你想把它挂在子路径（例如和别的服务共用域名），
把 `prefix` 改成 `/litv`，再把下面 nginx 段的 `location /` 换成 `location /litv/`，客户端地址也要带 `/litv`。

直播是长连接，1Panel 的反代默认配置容易把它掐断，进「网站 → 配置文件」补上：

```nginx
location / {
    proxy_pass http://127.0.0.1:28027;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # 直播流会挂很久；默认 60s 会让播放器每隔一分钟重连一次
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    # TS/HLS 分片不能被缓冲，否则起播要多等一整个分片
    proxy_buffering off;
    proxy_request_buffering off;
    # 分片与录制文件下载
    client_max_body_size 0;
}
```

保存后 `nginx -t` 通过即可（1Panel 会自动 reload）。

> 客户端（Android / iOS）里填的服务器地址就是域名本身：`https://域名`（**不要**带 `/app/litv` 这类前缀）。
> 纯内网也可以直接填 `http://内网IP:28027`（不经过反代，`prefix` 为空时路径一致）。
> 如果你把 `prefix` 改成了 `/litv`，客户端和浏览器都要带 `/litv`。

---

## 5. 首次登录与初始化

1. 浏览器打开 `https://域名/` → 用 `auth.admin_password` 登录
2. **源管理**：添加你自己的直播源（LITV 不内置任何源）
3. **EPG**：填 XMLTV 地址，或让默认 URL 列表生效
4. **设备**：生成绑定码 → 客户端首次启动时输入（6 位）
5. 客户端「关于」页确认版本与服务端一致

---

## 6. 验收清单

```bash
# 1) 容器活着：API 探活 + 管理台首页 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:28027/api/v1/health
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:28027/

# 2) 版本号
docker exec litv /app/litvd -version

# 3) ffmpeg 在里面（remux / 录制 / 时移都要它）
docker exec litv /usr/bin/ffmpeg -version | head -1

# 4) 数据真的落在宿主机上
ls -l /opt/litv/data          # 应该有 litv.db、config.yaml、cache/…
```

播放侧：用一台设备绑定 → 打开任一自建源频道 → 能看到画面即通。
iOS 与只认 HLS 的播放器走 `/remux/` 路径，这条路依赖 ffmpeg，验收时**至少用 iOS 或网页播放器试一次**。

---

## 7. 数据、备份与容量

`data/` 下的内容全部可迁移：

```
data/config.yaml        配置（含签名密钥 secret；别泄露）
data/litv.db            SQLite：源、频道、设备、EPG、录制记录
data/cache/             环形缓冲落盘、转封装临时分片
data/timeshift/         时移分片（看过的台才有）
data/recordings/        录制文件（最占空间）
```

- 备份：停容器 → 打包 `data/` → 起容器（SQLite 是 WAL 模式，运行中直接拷可能拿到不一致快照）
- 清理：时移有 `max_segments`（默认 12 段 × 64 MB ≈ 768 MB/频道，只在被观看时产生）；
  录制不会自动删，靠管理台手动删或自己加 cron
- 迁移到另一台机器：把 `data/` 整份搬过去，配置里的路径不用改（容器内绝对路径）

---

## 8. 排错

| 现象 | 原因与处理 |
|---|---|
| 容器起来就退出，日志 `数据库打开失败 … permission denied` | `data/` 属主不是 10001。`chown -R 10001:10001 data` |
| 登录一直提示"密码错误" | `auth.admin_password` 为空（空密码 = 登录被禁用），或改完配置没重启容器 |
| 管理台 404 | 访问路径与 `server.prefix` 不一致：`prefix` 为空时管理台就在 `http://IP:28027/`；填了 `/litv` 就必须访问 `/litv/` |
| 反代 502 | 容器没起或端口没映射。`curl 127.0.0.1:28027/api/v1/health` 先确认后端活着 |
| 播放一分钟就断一次 | 反代 `proxy_read_timeout` 还是默认 60s，见 §4 |
| 起播慢、容易卡 | `proxy_buffering` 没关；或宿主机上行不够（`docker stats` 看网络） |
| iOS 黑屏、安卓正常 | `/remux/` 失败，基本是 ffmpeg 缺失或版本太老；`docker exec litv ffmpeg -version` |
| 节目单空 | EPG 地址没配或拉取失败；管理台 EPG 页看最近一次刷新结果 |
| 时间显示差 8 小时 | `server.timezone` 必须是 `Asia/Shanghai` 这类 IANA 名，不是 `+08:00` |
| 端口被占 | 服务端会自动顺延端口并写进日志，但容器映射的是 28027，建议改宿主端口不用顺延 |

---

## 9. 升级

```bash
cd /opt/litv && git pull
docker compose up -d --build     # 或用 1Panel 编排里的「重建」
```

`data/` 不受影响。**但不要改 `auth.secret`**：改了之后所有已下发的播放地址立刻失效（客户端表现为 403）。

---

## 10. 本次交付的验证边界

- 已核对：`Dockerfile` 的构建步骤与 `packaging/fnos/build.sh` 的 embed 约定一致（`rm -rf` 旧的
  `server/internal/web/www` 再拷 `admin/dist`）；`config/config.example.yaml` 的全部键与
  `server/internal/config/config.go` 的 yaml tag 逐一对应；compose 的路径、端口、uid 与
  `Dockerfile` 一致。
- **镜像已在 GitHub Actions 里真实构建并推送**（2026-11-28）：
  `ghcr.io/ybd156/litv:0.1.24` 与 `:latest` 同 digest `sha256:098f12a1…a5e92a`，
  manifest 为 `linux/amd64`（另有 buildkit 的 attestation manifest），
  匿名拉取 `HEAD /v2/ybd156/litv/manifests/0.1.24` 返回 200 ⇒ 包是 public，服务器免登录可 pull。
  工作流：`.github/workflows/docker-image.yml`（run 46087667511，全套步骤 success，约 2.5 分钟）。
- 本机执行 `docker build` / `docker compose up` 的路径**仍未在本机验证**（本机没有 Docker）；
  上面这条 CI 构建是「镜像能构建出来」的证据，但「容器在你的 1Panel 上跑起来」还需要你那边首次启动。
- 目前只发布 `linux/amd64`。ARM NAS（飞牛 ARM 机型）需要把工作流里的
  `platforms` 改成 `linux/amd64,linux/arm64` 并加 QEMU 步骤，构建时间会涨到 25 分钟以上。
