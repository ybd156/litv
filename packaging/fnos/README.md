# LITV 飞牛 fnOS 原生应用包（.fpk）

把 LITV 打包成飞牛 fnOS 可以**在应用中心直接安装**的原生应用。

- 服务端：Go 单二进制 `litvd`（无 systemd 依赖，由 `cmd/main` 自己拉起/守护）
- 入口一：**飞牛统一网关** `/app/litv` → `target/app.sock`（管理台，免二次登录，网关注入 `X-Trim-*` 身份）
- 入口二：**独立 TCP 端口**（默认 9527）→ Android TV / 手机 / iOS 原生客户端直连
- 数据：SQLite，落在 `$TRIM_PKGVAR`（跨重启、跨升级保留）

---

## 一、目录结构

```
packaging/fnos/
├── manifest                      # 应用清单（key=value，非 YAML）
├── ICON.PNG                      # 包图标 64x64
├── ICON_256.PNG                  # 包图标 256x256
├── build.sh                      # ★ 一键打包
├── README.md                     # 本文
├── .gitignore                    # 忽略 .build/ dist/ *.fpk 与二进制
├── cmd/                          # 生命周期脚本（9 个，官方要求全有）
│   ├── main                      # ★ start|stop|restart|reload|status|watchdog
│   ├── install_init              # 安装前检查
│   ├── install_callback          # 生成 config.yaml / secret / 数据目录
│   ├── config_init
│   ├── config_callback           # 改端口/密码/转码 → 重启
│   ├── upgrade_init              # 停服 + 备份旧配置
│   ├── upgrade_callback          # 补配置 + 启动
│   ├── uninstall_init            # 停服
│   └── uninstall_callback        # 按用户选择保留/删除数据
├── config/
│   ├── privilege                 # run-as: package + join-groups [video, render]（QSV 硬解）
│   └── resource                  # data-share: litv/recordings、litv/playlists
├── wizard/
│   ├── install                   # 向导：端口 / 管理员密码 / 媒体库目录
│   ├── config                    # 设置页：改端口 / 改密码 / 开关转码
│   └── uninstall                 # 保留 or 删除数据
├── i18n/
│   ├── zh-CN
│   └── en-US                     # ★ 有 i18n 目录就**必须**有 en 或 en-US
├── app/
│   ├── server/                   # 构建时放入 litvd_x86_64 / litvd_aarch64
│   │   ├── .gitkeep
│   │   └── config.yaml.template  # 服务端配置模板（带 @@标记@@）
│   ├── www/                      # 构建时放入管理台静态文件
│   └── ui/                       # 桌面入口（desktop_uidir）
│       ├── config                # ★ 网关入口注册
│       └── images/icon_{64,256}.png
└── tools/
    ├── make-icons.py             # 生成图标（PIL）
    ├── smoke-test.sh             # ★ 本地生命周期冒烟测试（不需要飞牛机器）
    └── litvd-stub/main.go        # 占位服务端（server/ 还没有代码时用它出包）
```

---

## 二、构建

### 前置依赖

| 依赖 | 说明 |
|---|---|
| `fnpack` | 官方打包器，**1.2.3**（1.0.4 也能用）。脚本会自动找 `tools/fnpack` → `PATH` → 自动下载 |
| `go` | 1.23+，编译 `litvd`（静态二进制，`CGO_ENABLED=0`） |
| `python3` + `Pillow` | 仅生成图标时用（图标已入库，日常构建不需要） |
| `curl` / `tar` / `sha256sum` | 常规 |

`fnpack` 手动安装（WSL / Linux）：

```bash
curl -fL -o tools/fnpack https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64
chmod +x tools/fnpack
tools/fnpack --help | head -2      # 应打印 Version 1.2.3
```

### 打包

```bash
cd packaging/fnos

./build.sh                  # x86 包  → dist/litv_x86_v1.0.0.fpk
./build.sh --arch arm       # ARM 包  → dist/litv_arm_v1.0.0.fpk
./build.sh --arch all       # 双架构  → dist/litv_all_v1.0.0.fpk（包体约翻倍）
./build.sh --skip-build     # 复用已有二进制，只重打 .fpk（调包结构时很快）
./build.sh --version 1.2.3  # 临时改版本号（不动仓库里的 manifest）
```

产物：

```
dist/litv_x86_v1.0.0.fpk
dist/litv_x86_v1.0.0.fpk.sha256     # sha256sum 格式，可直接 sha256sum -c
```

### build.sh 做了哪些事（顺序）

1. 找 / 必要时下载 `fnpack`，打印版本
2. 校验 `manifest` 必需字段；校验 `desktop_applaunchname` 与 `app/ui/config` 的入口 ID **完全一致**且以 `litv.` 开头（后者是 fnpack 硬校验）
3. 校验 i18n 引用完整性：把 `manifest` / `app/ui/config` / `wizard/*` 里所有 `${section.key}` 提出来，逐个核对 `i18n/zh-CN` 和 `i18n/en-US` 是否有对应 key
4. 编译二进制：`server/` 有 Go 代码就编真实服务端；没有就编 `tools/litvd-stub`（可安装可启动，用来验证链路）
5. 找管理台静态文件（`web/dist` → `admin/dist` → `web/build` → `admin/build`），都没有就生成一个占位首页
6. **在干净的 `.build/stage/` 里组装**，按 `--arch` 改写 `platform`，清掉 `.gitkeep` / `.DS_Store`
7. `fnpack build -d .build/stage`
8. 改名成 `litv_<arch>_v<ver>.fpk`，生成 `.sha256`，打印包内清单

> **为什么必须用 staging 目录**：fnpack 会把"包目录里除 `manifest`/`ICON*`/`cmd`/`wizard`/`i18n` 之外的一切"塞进 `app.tgz`。在源码目录直接打包，`.build/`、`dist/`、`.gitignore` 全会进安装包。

---

## 三、安装到飞牛

### 方式 A：应用中心手动安装（推荐给普通用户）

1. 把 `dist/litv_x86_v1.0.0.fpk` 拷到能访问的共享目录，或直接在飞牛 Web 界面里上传
2. **应用中心 → 手动安装 → 选择 .fpk**
3. 按向导填：服务端口（默认 9527）、管理员密码、媒体库目录（可留空）
4. 装完桌面出现 **LITV 直播管理** 图标，点开即进管理台

### 方式 B：命令行（推荐给运维）

```bash
# 传到 NAS
scp dist/litv_x86_v1.0.0.fpk admin@nas:/tmp/

# SSH 登录 NAS 后
appcenter-cli install-fpk /tmp/litv_x86_v1.0.0.fpk
appcenter-cli list                # 看状态
appcenter-cli start litv          # 启动
appcenter-cli stop litv           # 停止
```

在设备上的源码目录里迭代（`fnpack build` 产物就在当前目录）：

```bash
appcenter-cli install-local       # 需要把 packaging/fnos 拷到 NAS 上
```

### 访问

| 入口 | 地址 | 鉴权 |
|---|---|---|
| 统一网关（管理台） | `https://<nas域名>/app/litv` | 飞牛登录态，网关注入 `X-Trim-Userid` / `X-Trim-Username` / `X-Trim-Isadmin` |
| TCP 直连（原生客户端） | `http://<nas-ip>:9527` | LITV 自建 device token |
| 健康检查 | `/app/litv/api/v1/health` 或 `http://<nas-ip>:9527/api/v1/health` | 无 |

---

## 四、调试与排障

### 安装后的目录布局

```
/var/apps/litv/
├── cmd/                 # 生命周期脚本
├── config/{privilege,resource}
├── manifest
├── ICON.PNG / ICON_256.PNG
├── target -> /vol{n}/@appcenter/litv    # 程序（app.tgz 展开处）
│   ├── server/litvd_x86_64
│   ├── server/config.yaml.template
│   ├── ui/config                        # 桌面入口
│   ├── www/                             # 管理台静态文件
│   └── app.sock                         # ★ 网关 Unix Socket（运行后才有）
├── etc    -> /vol{n}/@appconf/litv      # config.yaml 在这里
├── var    -> /vol{n}/@appdata/litv      # litvd.log / litvd.pid / data/ / server_port
├── tmp / home / meta / shares / wizard
```

### 三条命令定位问题

```bash
# 1) 服务状态（退出码：0 运行中 / 3 未运行 / 1 失败）
/var/apps/litv/cmd/main status; echo "exit=$?"

# 2) 日志（服务端 stdout/stderr 全部重定向到这里，>10MB 自动轮转为 litvd.log.1）
tail -f /var/apps/litv/var/litvd.log

# 3) 绕过网关直接打 socket，判断是"服务没起来"还是"网关没配上"
curl -s --unix-socket /var/apps/litv/target/app.sock \
     http://localhost/app/litv/api/v1/health
# 期望: {"code":0,"msg":"ok","data":{...}}
```

### 手工启停

```bash
/var/apps/litv/cmd/main start|stop|restart|reload|status|watchdog
```

- `reload`：先发 `SIGHUP`；服务端若没实现信号重载（默认动作是退出）会自动退化成 restart
- `watchdog`：进程不在就拉起，带崩溃环保护（10 分钟内最多 5 次）。可以挂到 cron：
  `*/2 * * * * /var/apps/litv/cmd/main watchdog`

### 本地冒烟测试（不需要飞牛机器）

```bash
cd packaging/fnos
./build.sh
./tools/smoke-test.sh dist/litv_x86_v1.0.0.fpk
```

脚本会解包到 `/tmp/litv-smoke`，模拟 `/var/apps/litv` 布局，用真实 `TRIM_*` 变量跑 14 组用例：
install → start → 网关探活 → 身份 Header 透传 → 双实例防护 → `kill -9` 残留 socket 自愈 → 日志轮转 → 改配置重启 → stop → 卸载保留/删除 → 升级重启。端口自动挑空闲的，不会撞开发机上的调试进程。

### 关键运行时契约（改服务端时别改坏）

`cmd/main` 给子进程注入的环境变量：

| 变量 | 值 | 用途 |
|---|---|---|
| `TRIM_APPDEST` / `TRIM_PKGVAR` / `TRIM_PKGETC` | fnOS 注入 | 服务端据此找 `config.yaml`（`DiscoverPath`）与相对路径基准 |
| `LITV_LISTEN_SOCKET` | `${TRIM_APPDEST}/app.sock` | 网关 socket 绝对路径 |
| `LITV_URL_PREFIX` | `/app/litv` | 必须与 `gatewayPrefix` 一致 |
| `LITV_LISTEN_ADDR` | `0.0.0.0:<port>` | 原生客户端 TCP 端口 |
| `LITV_DATA_DIR` / `LITV_ETC_DIR` / `LITV_CONFIG` / `LITV_WWW_DIR` / `LITV_LOG_FILE` | 对应目录 | 便于服务端/占位实现统一取路径 |

端口来源优先级：`$TRIM_PKGVAR/server_port`（`install_callback` 写入）→ 默认 `9527`。

---

## 五、常见问题

**Q1：桌面图标点开白屏 / 一直转圈 / "重定向次数过多"**
先绕过网关直连 socket 打 `/app/litv/api/v1/health`。
- 200 → 服务正常，问题在**静态资源/路由**（典型：首页 301 死循环、前端 `base` 没带结尾斜杠）
- 000/502 → 服务没起来，看 `var/litvd.log`

**Q2：网关 404，但 socket 存在**
`gatewayPrefix` 与后端注册前缀必须**完全一致**（本包统一 `/app/litv`）。
注意 `app/ui/config` 的 `gatewaySocket` 只写文件名 `app.sock`，不要写绝对路径。

**Q3：第二次点启动起不来，日志里 `bind: address already in use`**
被 `SIGKILL` 的进程不会清理 socket 文件。`cmd/main start` 已经 `rm -f` 过旧 socket，
如果仍然报错，说明有**游离进程**还活着：
```bash
pgrep -af litvd
kill -9 <pid>; rm -f /var/apps/litv/target/app.sock
/var/apps/litv/cmd/main start
```

**Q4：启动超时 "等待 socket 超过 10 秒"**
`cmd/main` 以 **socket 就绪**为成功判据（不是"进程还在"），所以超时意味着服务端没建 socket：
- `config.yaml` 里 `server.gateway_socket` 路径与 `$TRIM_APPDEST/app.sock` 不一致（换过存储空间最容易踩）
- `$TRIM_APPDEST` 不可写（权限/挂载问题）
- 服务端启动即退出（看日志最后几行，`cmd/main` 会把原因写进 `$TRIM_TEMP_LOGFILE` 给应用中心显示）

**Q5：TCP 端口被占用**
`manifest` 里 `checkport = false`，框架不做端口预检查。`cmd/main` 只在日志里告警、不阻断启动——
因为**管理台走网关，不依赖这个端口**。要换端口：应用设置页改，或直接改 `etc/config.yaml` 的 `server.port` 后 `cmd/main restart`。

**Q6：应用中心把描述显示成 `${common.displayName}`**
i18n 的 key 没对上。fnpack **不校验** `${...}` 引用是否存在（只校验 i18n 目录里必须有 `en`/`en-US`），
所以漏写 key 会原样显示。`build.sh` 里的 i18n 引用检查就是防这个。

**Q7：图标不显示 / 很糊**
- 包图标：`ICON.PNG` 64×64、`ICON_256.PNG` 256×256（官方规范，**不是**两个都 256）
- 桌面入口图标：`app/ui/images/icon_64.png` 与 `icon_256.png` 必须都存在，`ui/config` 里写的是 `images/icon_{0}.png`
- 单文件 ≤ 1024KB、sRGB、视觉主体圆角矩形

**Q8：卸载后数据还在 / 想彻底清干净**
卸载向导选"删除全部数据"才会清。选"保留"时只删 PID/锁/日志，数据库、配置、媒体库都留着（方便重装恢复）。
手工清：`rm -rf /var/apps/litv/{var,etc}` 以及媒体库目录。

**Q9：升级后配置没了 / 密码变了**
`install_callback` 幂等：`config.yaml` 已存在时**不会**重新生成 `auth.secret` 和密码（否则所有已登录会话失效）。
`upgrade_init` 还会把旧配置备份成 `config.yaml.bak.<旧版本>`。

**Q10：`status` 返回 3，但 `ps` 能看到 litvd**
`status` 会校验 `/proc/<pid>/cmdline` 里是否含 `litvd`（防机器重启后 PID 复用误判）。
如果是手工 `./litvd` 起的（不在 `server/` 目录、PID 文件也没有），框架和脚本都不认，属于预期行为。

**Q11：硬解不生效（QSV）**
`config/privilege` 已声明 `join-groups: ["video", "render"]`；还需要：
- 宿主机有 `/dev/dri/renderD128` 且应用用户可读
- `transcode.enabled: true` + `transcode.hwaccel: "qsv"`
- `transcode.ffmpeg` 指向真实 ffmpeg（`/usr/bin/ffmpeg` 或应用自带）

---

## 六、与官方文档 / 参考实现的差异（踩坑记录）

这些是实测出来的，和社区常见写法不一样，改动前请先看这里。

| 项 | 参考实现（fnos-mediaplayer） | 本包做法 | 原因 |
|---|---|---|---|
| `ICON.PNG` 尺寸 | — | **64×64** | 官方《图标》明确 `ICON.PNG` 64×64、`ICON_256.PNG` 256×256。两个都放 256 不符合规范 |
| `config_callback` 重启服务 | `"${TRIM_APPDEST}/cmd/main" restart` | `"${SCRIPT_DIR}/main" restart` | `cmd/` 在 `/var/apps/litv/cmd`，**不在** `target/`（`TRIM_APPDEST`）。参考实现那句 `-x` 判断永远为假，重启其实**从来没生效过** |
| `manifest` 的 `reloadui` | 用了 | 未用 | 官方字段表里没有该字段。fnpack 不校验，会原样透传，属于未证实字段 |
| `manifest` 的 `checksum` | 手写/留空 | 不写 | `fnpack build` 会自动追加 `checksum = md5(app.tgz)` |
| 入口 ID | `mediaplayer.Application` | `litv.main` | fnpack 硬校验：入口 ID 必须以 appname 开头且 ≤32 字符；官方《应用入口》建议 `{appname}.main` |
| `desktop_applaunchname` | 与入口 ID 一致 | 一致 | 不一致时 fnpack 直接报错；一致但拼错会点开空白页 |
| `platform` | `all` | 按 `--arch` 分别打 `x86`/`arm`/`all` | 官方：`all` **仅当包内无架构相关二进制**。包里带 Go 二进制就不该标 `all` |
| 端口检查 | — | `checkport = false` | 走网关不监听固定端口，避免框架做无意义的端口检查 |
| 配置生成 | `sed s/旧值/新值/` | 行尾 `# @@标记@@` + awk `ENVIRON` 整行替换 | 密码里的 `/ & \ $` 会把 `sed` 搞坏，这是"改个密码就装不上"的头号原因 |
| 卸载删数据 | 直接 `rm -rf $wizard_data` | 白名单校验 + `find -mindepth 1 -delete` 清内容 | ① 向导路径来自用户输入，`rm -rf` 太危险；② `$TRIM_PKGVAR` 是**软链接**，`rm -rf` 只删链接、数据留在存储空间变垃圾 |
| i18n | 只有 `zh-CN` | `zh-CN` + `en-US` | 有 `i18n/` 目录就**必须**有 `en` 或 `en-US`，否则 `fnpack build` 直接失败（报 "The i18n directory must contain en or en-US file."） |

另外两条来自 fnpack 逆向/实测、官方文档没写的：

- `fnpack build` 会把包目录里**除** `manifest`/`ICON*`/`cmd`/`wizard`/`i18n` 之外的所有东西塞进 `app.tgz`，连 `.DS_Store` 都进 —— 所以必须用干净 staging 目录
- `app.tgz` 内部**没有** `app/` 顶层目录，直接就是 `server/`、`ui/`、`www/`、`config/`，安装后展开成 `target/`

---

## 七、安全须知

- `auth.secret` 安装时随机生成（`openssl rand -hex 32`），**不要**写死在模板里
- 未填管理员密码时，随机密码写到 `$TRIM_PKGVAR/initial_admin_password`（0600），不回显到安装日志
- 网关 socket 权限 `0660`：只有网关进程（同组）能连，同机其他用户连不进来
- 服务端**必须**用网关注入的 `X-Trim-Userid` 做身份，不能信任客户端自报的 uid / query 参数
- `auth.dev_mode` 生产必须 `false`（它会在没有网关头的降级成本地管理员）
- 不要把 `config.yaml`（含 secret 与密码）、数据库、`var/litvd.log` 暴露成静态资源
