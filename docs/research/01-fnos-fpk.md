# 飞牛 fnOS 第三方应用开发与 .fpk 打包规范调研

> 调研对象：飞牛私有云 fnOS（第三方应用 / `.fpk` 包格式 / 统一网关 / 账号体系接入）
> 目标场景：Go 单文件二进制 + 内置 Web 管理台的 IPTV 多终端管理后台（LITV2），打包为原生 `.fpk`，安装到 Intel N100 的飞牛 NAS，并"接入飞牛账号管理"实现免登录/SSO。
> 调研日期：2026-11-01。所有结论均附出处 URL；查不到的一律标注「未找到公开资料」或「未证实」。

---

## 一、结论摘要（TL;DR）

1. **官方有正式开发者平台，文档齐全，别去翻论坛猜**。入口是 <https://developer.fnnas.com/>，分「开发文档 /docs/guide/」「开放 API /api/overview/」「更新日志」。还提供机器可读全量快照 `https://developer.fnnas.com/llms.txt` 与 `llms-full.txt`，写代码前抓这个比逐页翻省事。

2. **`.fpk` 就是一个 tar.gz**（成员是"包根条目"，没有顶层目录）。真正的应用文件不在包里散放，而是打成一个 `app.tgz` 放在包根，`manifest` 里用 `checksum = md5(app.tgz)` 校验。官方不公开这个格式，但社区提取器与构建脚本互相印证。**不要手搓 fpk，用官方 `fnpack`**（当前 v1.2.3）。

3. **打包工具 `fnpack` 是官方唯一的正确姿势**，`fnpack create <name>` 生成骨架、`fnpack build` 出包。下载：`https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64`（另有 windows/arm64/darwin）。设备端安装用 `appcenter-cli install-fpk xxx.fpk`。

4. **没有 systemd / supervisor**。飞牛不用 systemd 托管第三方应用，而是**自己调用 `cmd/main start|stop|status`**，靠退出码（0 运行 / 3 未运行 / 1 失败）判断状态。**守护进程、PID 文件、崩溃拉起全部要你自己在 `cmd/main` 里实现**——这是和"写个 systemd unit"最大的思维差异。

5. **要"接入飞牛账号管理"，官方唯一正路是「统一网关」**。应用注册 `gatewayPrefix=/app/<appname>` + `gatewaySocket=app.sock`，监听 `${TRIM_APPDEST}/app.sock` 这个 Unix Socket；飞牛网关**先校验 NAS 登录态，再转发请求，并注入三个身份 Header**：`X-Trim-Userid`、`X-Trim-Isadmin`、`X-Trim-Username`。这就是官方支持的"免登录"——用户登录过 fnOS 桌面，点开你的应用就是已登录状态。

6. **官方没有任何 OAuth / 登录 SDK**。开放 API 第一期只有文件授权、页面路由、界面状态、平台配置；官方文档明确"登录集成"尚未开放。想拿"飞牛账号当 IdP"给别的系统用，只能走社区反向工程方案（`fnos-oidc-bridge`），**实验性、随版本可能失效，不建议作为主路径**。

7. **网关会忽略 `protocol` 和 `port`**。走统一网关时 `manifest` 里可以不写 `service_port`，也不占宿主端口——对 IPTV 这种要跟别的服务抢端口的场景是好事。但代价是：**应用必须自己按 `/app/<appname>` 前缀注册路由，前端构建 base 也必须是 `/app/<appname>/`**，这是社区里翻车最多的地方（白屏 / 404）。

8. **WebSocket 官方支持**，复用同一个前缀和同一个 Socket，建议放 `/app/<appname>/ws`。`index.cgi` 模式**不支持** WebSocket（每次请求起一个进程），别选它。

9. **数据/配置/日志目录是框架给的环境变量，不要硬编码**：`TRIM_APPDEST`（程序目录）、`TRIM_PKGETC`（配置）、`TRIM_PKGVAR`（持久数据）、`TRIM_PKGTMP`、`TRIM_PKGHOME`。物理位置是 `/vol{n}/@appcenter|@appconf|@appdata|@apphome/{appname}`，通过 `/var/apps/{appname}/{target,etc,var,tmp,home}` 软链访问。**没有官方日志目录**，惯例写 `$TRIM_PKGVAR/*.log`。

10. **默认非 root 运行**（`config/privilege` 里 `run-as: package`）。要用 N100 核显做 QSV/VAAPI 硬解，官方给的答案是 `join-groups: ["video","render"]` 把应用用户加进这两个系统组——**不要为了硬解去开 root**。

11. **N100 上原生 fpk 优于 Docker**：Go 静态单文件零依赖，`platform=x86` 即可，内存/启动开销远小于容器；Docker 模板虽然官方支持（`fnpack create --template docker`），但会多一层 `/dev/dri` 设备映射与镜像架构匹配的坑，且容器内进程身份不受 `privilege` 管控。

---

## 二、fnOS 是什么 / 底层 / 应用安装机制

### 2.1 定位与体量

飞牛 fnOS 是国产 NAS 操作系统，2024 年 8 月开放公测；官方称截至 2025 年 8 月装机量超 **90 万**、APP 设备数超 **120 万**，目标是做"存储系统界的 Windows"。
出处：<https://developer.fnnas.com/docs/guide/>

### 2.2 底层

- **发行版基础：Debian**。社区实测环境为 `fnOS 1.2.0505 / Debian 12 / PostgreSQL 15`（见 `fnos-oidc-bridge` README 的"快速开始"一节）：<https://github.com/BeFortune/fnos-oidc-bridge>
- 另有社区文章明确写"fnOS 是基于 Debian 构建的"，VMware 安装时按 Debian 12.x 64 位选：<https://charles7c.top/posts/2025/12/install-fnos-with-vmware.html>
- **内核版本随系统版本演进**：0.9.2 版本（2025-05）把 Linux 内核升到 **6.12.18**：<https://www.ithome.com/0/851/973.htm>。更早版本有 6.6.x 的记录。**具体内核以目标机实测为准**，运行时可通过环境变量 `TRIM_KERNEL_VERSION` 读到。
- 系统自身服务用 Go 写（`trim.accountsrv` 为 Go + gorm/pgx，见社区侦察记录），自带 nginx 位于 `/usr/trim/nginx/conf/`，多数内部请求走 Unix Socket 转发。

> 注意：官方文档没有一页专门写"底层是 Debian 几"，以上来自社区实测与媒体稿。对我们打包的影响其实很小——**Go 静态编译 + 不依赖发行版包管理器**就能绕开所有发行版差异。

### 2.3 应用安装机制（三条路）

| 方式 | 命令/入口 | 适用 |
|---|---|---|
| 应用中心「手动安装」 | 桌面 → 应用中心 → 右上角手动安装 → 选 `.fpk` | 本地测试；官方明确"仅用于本地测试，不应作为公开分发方式" |
| 设备端 CLI | `appcenter-cli install-fpk xxx.fpk`（可加 `--env config.env` 灌向导变量） | 脚本化 / CI |
| 本地目录直装 | `cd /path/to/myapp && appcenter-cli install-local` | 开发期在设备上迭代最快 |

出处：<https://developer.fnnas.com/docs/quick-started/test-application/>、<https://developer.fnnas.com/docs/cli/appcentercli/>

`appcenter-cli` 还有 `list` / `start <app>` / `stop <app>` / `default-volume [n]`。

### 2.4 上架

开发者后台（"我的应用"）在调研时仍标着 Coming Soon，实际路径是：加入飞牛粉丝群 → 联系社区主理人 → 进「应用中心开发者先锋交流群」→ 按指引提交包与截图。
出处：<https://developer.fnnas.com/docs/quick-started/publish-application/>

---

## 三、`.fpk` 包结构

### 3.1 源码工程结构（`fnpack create` 生成的骨架）

```
myapp/
├── app/
│   ├── ui/                     # desktop_uidir，默认 ui
│   │   ├── config              # 应用入口（桌面图标/文件打开方式/网关前缀）
│   │   └── images/             # icon_64.png / icon_256.png 等
│   └── docker/
│       └── docker-compose.yaml # 仅 --template docker
├── cmd/                        # 生命周期脚本（见 3.3）
│   ├── main
│   ├── install_init
│   ├── install_callback
│   ├── upgrade_init
│   ├── upgrade_callback
│   ├── uninstall_init
│   ├── uninstall_callback
│   ├── config_init
│   └── config_callback
├── config/
│   ├── privilege               # 运行身份（JSON）
│   └── resource                # 资源与 api-scope（JSON）
├── wizard/                     # install / upgrade / uninstall / config 表单
├── manifest                    # 无扩展名，key=value
├── ICON.PNG
└── ICON_256.PNG
```

出处：<https://developer.fnnas.com/docs/cli/fnpack/>

### 3.2 `fnpack build` 的强制校验清单

打包前会检查，缺一个就失败：

| 路径 | 要求 |
|---|---|
| `manifest` | 存在，且包含必要字段 |
| `config/privilege` | 存在，合法 JSON |
| `config/resource` | 存在，合法 JSON |
| `ICON.PNG` | 存在 |
| `ICON_256.PNG` | 存在 |
| `app/` | 目录存在 |
| `cmd/` | 目录存在 |
| `wizard/` | **目录存在**（哪怕空目录，也要建） |
| `app/{desktop_uidir}/` | 声明了 `desktop_uidir` 时必须存在 |

出处：<https://developer.fnnas.com/docs/cli/fnpack/>

> 实践提示：`wizard/` 是很多人第一次 `fnpack build` 失败的原因——空目录也要有。

### 3.3 生命周期脚本

| 脚本 | 时机 |
|---|---|
| `install_init` | 应用文件落地**前** |
| `install_callback` | 应用文件落地**后** |
| `main` | `start` / `stop` / `status` |
| `upgrade_init` / `upgrade_callback` | 升级前 / 后 |
| `uninstall_init` / `uninstall_callback` | 卸载前 / 清理后 |
| `config_init` / `config_callback` | 配置变更前 / 后 |

- 所有脚本要**可重复执行**（框架可能重跑）。
- 失败时把用户可读的错误写进 `$TRIM_TEMP_LOGFILE` 再 `exit 非0`，应用中心会把这句话显示给用户。
- `status` 退出码：`0` 运行中 / `3` 未运行 / `1` 失败。
- 静态应用可 `manifest` 里 `ctl_stop=false` 隐藏启停按钮。

出处：<https://developer.fnnas.com/docs/core-concepts/framework/>

**重要工程含义**：没有 systemd。你的 Go 服务必须由 `cmd/main start` 自己 `nohup ... &` 起来、写 PID 文件、`stop` 时杀进程、`status` 时校验进程存活。所谓"开机自启"= 飞牛启动时调 `cmd/main start`。

### 3.4 `.fpk` 包内的真实布局（官方未文档化，社区交叉验证）

`.fpk` = **tar.gz，成员为包根条目、无顶层目录**；`manifest.checksum` = `md5(app.tgz)`。

```
（tar.gz 根）
├── manifest          # 含 checksum = md5(app.tgz)
├── app.tgz           # 真正的应用负载，安装后展开为 target/
├── cmd/              # 生命周期脚本
├── config/           # privilege / resource
├── wizard/
├── ui/               # 桌面入口 + images（部分包把它放 app/ui 再由打包器搬）
├── var/              # 可选：种子文件，安装时同步到 TRIM_PKGVAR
├── *.sc              # 可选：端口转发配置（格式未公开）
├── ICON.PNG
└── ICON_256.PNG
```

出处（两处独立印证）：
- 「应用提取器」README：「`.fpk` 就是 tar.gz，成员为包根条目、没有顶层目录；`manifest.checksum` = `md5(app.tgz)`」：<https://github.com/cjm2004/fnos-fpk-extractor>
- `conversun/fnos-apps` 的通用打包器 `scripts/build-fpk.sh`：先算 `md5(app.tgz)` 写进 `checksum`，再 `tar -czf` 打包根目录下的 `manifest / app.tgz / cmd / config / wizard / ui / ICON*.PNG / var / *.sc`：<https://github.com/conversun/fnos-apps/blob/main/scripts/build-fpk.sh>

> 结论：**用 `fnpack build` 就够，不需要了解 tar 细节**。但知道"`target/` 内容来自 `app.tgz`"对排查安装问题很有用——`app.tgz` 太小（<10KB）基本就是下载损坏或构建脚本漏拷。

### 3.5 安装后的运行目录

```
/var/apps/{appname}
├── cmd/                       # 生命周期脚本
├── config/{privilege,resource}
├── manifest
├── ICON.PNG / ICON_256.PNG
├── target -> /vol{n}/@appcenter/{appname}    # 程序文件（app.tgz 展开处）
├── etc    -> /vol{n}/@appconf/{appname}      # 配置
├── var    -> /vol{n}/@appdata/{appname}      # 需跨重启保留的运行时数据
├── tmp    -> /vol{n}/@apptemp/{appname}      # 临时
├── home   -> /vol{n}/@apphome/{appname}      # 应用用户数据
├── meta
├── shares/                    # config/resource 里声明的共享目录软链
└── wizard/{install,upgrade,uninstall,config}
```

出处：<https://developer.fnnas.com/docs/core-concepts/framework/>

---

## 四、manifest 字段（可确证部分）

`manifest` 是**无扩展名的 `key=value` 文本**（不是 TOML/YAML），放包根。

### 4.1 官方字段全表

| 字段 | 说明 |
|---|---|
| `appname` | 应用唯一标识 |
| `version` | 版本，如 `1.0.0` / `2.1.3-beta` |
| `display_name` | 应用中心/设置/UI 中显示名 |
| `desc` | 描述，必要时可用 HTML |
| `source` | 应用来源，第三方固定 `thirdparty` |
| `platform` | 架构：`x86` / `arm` / `all`（**`all` 仅当包内无架构相关二进制**） |
| `maintainer` / `maintainer_url` | 开发者与联系方式 |
| `distributor` / `distributor_url` | 发布者（与开发者不同时填） |
| `os_min_version` | 支持的最低 fnOS 版本 |
| `os_max_version` | 支持的最高版本（有明确上限才填） |
| `ctl_stop` | `true` 显示启停与运行状态；`false` 隐藏 |
| `install_type` | 空=用户选存储位置；`root`=装到系统分区 |
| `install_dep_apps` | 依赖应用，`:` 分隔，`>` 表示最低版本，如 `database>2.2.2:cache` |
| `desktop_uidir` | UI 目录，默认 `ui` |
| `desktop_applaunchname` | 多入口时，应用卡片点开哪个入口 ID |
| `service_port` | 应用服务端口（不监听固定端口可省略） |
| `checkport` | 启动前是否检查端口占用，默认 `true` |
| `disable_authorization_path` | `true` 隐藏"授权目录"设置 |
| `changelog` | 更新说明 |

出处：<https://developer.fnnas.com/docs/core-concepts/manifest/>

**版本变迁**：`arch` 字段已废弃，改用 `platform`（2025-12-31 更新日志）：<https://developer.fnnas.com/docs/update-log/>

### 4.2 社区观察到但**官方文档未列**的字段（标注「未证实」）

| 字段 | 观察到的用法 | 状态 |
|---|---|---|
| `checksum` | `checksum = md5(app.tgz)`，生产包 `IamAyang233/fnos-openvpn` 里为空值，社区打包器强制写入 | 社区确认存在；**是否被系统强制校验未证实** |
| `fpk_version` | 社区构建脚本可选追加 | 未证实 |
| `.sc` 文件 | 包根端口转发配置 | 未证实，格式未知 |

出处：<https://github.com/IamAyang233/fnos-openvpn/blob/main/fnos/manifest>（真实生产包，含 `checksum =` 空行）

### 4.3 最小可用 manifest（服务类 + 统一网关）

```ini
appname=litv
version=1.0.0
display_name=LITV 直播管理
desc=IPTV 多终端管理后台：频道源管理、终端编排、WebSocket 实时状态。
source=thirdparty
platform=x86
maintainer=Your Team
maintainer_url=https://example.com
distributor=Your Team
os_min_version=1.1.3100
desktop_uidir=ui
desktop_applaunchname=litv.main
ctl_stop=true
checkport=false
changelog=首个版本
```

要点：
- **走统一网关就不要写 `service_port`**，官方 Native 示例同样省略；`checkport=false` 避免框架做无意义的端口检查。
- `platform=x86`（不是 `all`）：包里带 x86_64 Go 二进制，必须声明架构，否则 ARM 机器装上会跑不起来。
- `os_min_version=1.1.3100` 是官方 Native 示例与生产包共同使用的取值（统一网关所需的最低版本量级），实际以你测试机版本为准。

### 4.4 `config/privilege`（运行身份）

```json
{
  "defaults": { "run-as": "package" },
  "username": "litv",
  "groupname": "litv",
  "join-groups": ["video", "render"]
}
```

- `run-as: package` → 以专用应用用户运行（默认、推荐）。
- `run-as: root` → 仅当生命周期脚本必须做特权准备时；**长期对外服务的进程仍应降权**（官方示例给的是 `runuser -u "$TRIM_USERNAME" -- ...`）。
- `join-groups`：官方原文点名"需要访问视频设备、GPU 渲染或硬件加速媒体输出的应用"可用 `video` / `render`。**这就是 N100 上跑 QSV 的官方姿势**。

出处：<https://developer.fnnas.com/docs/core-concepts/privilege/>

### 4.5 `config/resource`（资源与 Scope）

```json
{
  "data-share": {
    "shares": [
      { "name": "litv/recordings" },
      { "name": "litv/playlists" }
    ]
  },
  "usr-local-linker": {
    "bin": ["bin/litv-cli"]
  },
  "api-scope": [
    "trim.file.userAccess",
    "trim.file.userAcl"
  ]
}
```

- `data-share`：安装时自动创建共享目录（**Windows ACL 模型，非 POSIX ACL**），运行用户自动获得 ACL；路径可通过 `TRIM_DATA_SHARE_PATHS` 或 `/var/apps/<app>/share/...` 软链拿到。
- `usr-local-linker`：把 bin/lib/etc 软链到 `/usr/local/{bin,lib,etc}`。
- `docker-project`：Docker 应用声明 compose 项目。
- `api-scope`：调开放 API 前必须声明（见 6.2）。
- **未证实**：`resource` 是否有 schema 版本号或其他保留键；官方只列了上述几类。

出处：<https://developer.fnnas.com/docs/core-concepts/resource/>、<https://developer.fnnas.com/docs/api/calling/>

### 4.6 环境变量全表（打包/运行时最常用）

| 变量 | 含义 |
|---|---|
| `TRIM_APPNAME` / `TRIM_APPVER` / `TRIM_OLD_APPVER` | 应用名 / 当前版本 / 升级前版本 |
| `TRIM_APP_STATUS` | `INSTALL`/`START`/`UPGRADE`/`UNINSTALL`/`STOP`/`CONFIG` |
| `TRIM_APPDEST` | 已安装的 `target` 目录（**程序所在**） |
| `TRIM_PKGETC` | 配置目录 |
| `TRIM_PKGVAR` | 运行时数据目录（**持久**） |
| `TRIM_PKGTMP` | 临时目录 |
| `TRIM_PKGHOME` | 应用用户数据目录 |
| `TRIM_PKGMETA` | 元数据目录 |
| `TRIM_APPDEST_VOL` | 安装所在存储空间路径 |
| `TRIM_USERNAME` / `TRIM_GROUPNAME` / `TRIM_UID` / `TRIM_GID` | 应用用户/组 |
| `TRIM_RUN_USERNAME` / `TRIM_RUN_GROUPNAME` / `TRIM_RUN_UID` / `TRIM_RUN_GID` | 当前脚本执行者（特权操作时可能不同） |
| `TRIM_SERVICE_PORT` | `manifest.service_port` |
| `TRIM_DATA_SHARE_PATHS` | 共享目录，`:` 分隔 |
| `TRIM_DATA_ACCESSIBLE_PATHS` | 用户授权的可访问路径，`:` 分隔 |
| `TRIM_API_TOKEN` | 调开放 API 的 Bearer token（**每次从环境读，不要落盘**） |
| `TRIM_TEMP_LOGFILE` | 写用户可见错误 |
| `TRIM_TEMP_UPGRADE_FOLDER` / `TRIM_PKGINST_TEMP_DIR` / `TRIM_TEMP_TPKFILE` | 升级/安装临时目录 |
| `TRIM_SYS_VERSION(_MAJOR/_MINOR/_BUILD)` / `TRIM_SYS_ARCH` / `TRIM_KERNEL_VERSION` / `TRIM_SYS_MACHINE_ID` / `TRIM_SYS_LANGUAGE` | 系统上下文 |
| 向导字段名 | 原样成为环境变量（如 `wizard_port`） |

出处：<https://developer.fnnas.com/docs/core-concepts/environment-variables/>

---

## 五、LITV2 的最小可用 fpk 示例（可直接抄改）

### 5.1 `manifest`

见 4.3。

### 5.2 `config/privilege`

见 4.4（`join-groups` 按是否需要 QSV 决定，纯管理后台不需要）。

### 5.3 `config/resource`

见 4.5。

### 5.4 `app/ui/config`（统一网关入口）

```json
{
  ".url": {
    "litv.main": {
      "title": "LITV 直播管理",
      "icon": "images/icon_{0}.png",
      "type": "iframe",
      "protocol": "",
      "gatewayPrefix": "/app/litv",
      "gatewaySocket": "app.sock",
      "url": "/app/litv",
      "allUsers": true
    }
  }
}
```

- `gatewaySocket` 只写**文件名**，不是绝对路径。
- `gatewayPrefix` 用 `/app/{appname}`，跨版本保持稳定。
- 走网关时 `protocol` / `port` 被忽略。
- 想让入口只对管理员可见：`"allUsers": false` + `"control": {"accessPerm": "readonly"}`。

出处：<https://developer.fnnas.com/docs/core-concepts/gateway-registration/>、<https://developer.fnnas.com/docs/core-concepts/app-entry/>

### 5.5 `cmd/main`（Go 服务 + Unix Socket + 前缀）

```bash
#!/bin/bash
set -u

APPNAME="litv"
SOCK="${TRIM_APPDEST}/app.sock"
PREFIX="/app/${APPNAME}"
LOG="${TRIM_PKGVAR}/litv.log"
PIDF="${TRIM_PKGVAR}/litv.pid"

status_app() {
  [ -f "$PIDF" ] || return 1
  local pid; pid="$(tr -d '[:space:]' < "$PIDF")"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

start_app() {
  status_app && return 0
  rm -f "$SOCK" "$PIDF"            # 关键：旧 socket 不清会 bind 失败
  mkdir -p "$TRIM_PKGVAR"
  cd "$TRIM_APPDEST" || exit 1
  LITV_DATA_DIR="$TRIM_PKGVAR" \
  LITV_ETC_DIR="$TRIM_PKGETC" \
  LITV_LISTEN_SOCKET="$SOCK" \
  LITV_URL_PREFIX="$PREFIX" \
  nohup "$TRIM_APPDEST/bin/litvd" >> "$LOG" 2>&1 &
  echo $! > "$PIDF"
  # 等 socket 就绪，避免"启动成功但网关 502"
  for _ in $(seq 1 50); do
    [ -S "$SOCK" ] && exit 0
    sleep 0.2
  done
  echo "LITV 启动超时，请查看 $LOG" > "$TRIM_TEMP_LOGFILE"
  exit 1
}

stop_app() {
  if status_app; then
    kill "$(tr -d '[:space:]' < "$PIDF")" 2>/dev/null
    for _ in $(seq 1 50); do status_app || break; sleep 0.2; done
    status_app && kill -9 "$(tr -d '[:space:]' < "$PIDF")" 2>/dev/null
  fi
  rm -f "$PIDF" "$SOCK"
  exit 0
}

case "${1:-}" in
  start)   start_app ;;
  stop)    stop_app ;;
  status)  status_app && exit 0 || exit 3 ;;
  *)       echo "Unknown command: ${1:-}" > "$TRIM_TEMP_LOGFILE"; exit 1 ;;
esac
```

Go 侧对应要点：

```go
sock := os.Getenv("LITV_LISTEN_SOCKET")   // ${TRIM_APPDEST}/app.sock
prefix := os.Getenv("LITV_URL_PREFIX")    // /app/litv
os.Remove(sock)                            // 双保险
l, err := net.Listen("unix", sock)         // 注意：不是 tcp
os.Chmod(sock, 0o660)                      // 让网关进程可访问

// 路由必须同时挂在 "/" 和 prefix 下
mux := http.NewServeMux()
mux.Handle("/", rootHandler)
mux.Handle(prefix+"/", http.StripPrefix("", rootHandler)) // 或直接把 prefix 当根处理
// 关键：中间件里从 X-Trim-Userid 取身份，不要信任 query/body 里的 uid
```

> 社区经验（`leelaa` 的统一网关实录）：`gatewayPrefix` 与后端注册前缀**必须完全一致**，否则"网关能找到 socket 但路由 404"；Socket 权限建议 `0660`；启动前 `rm -f` 旧 socket；前端构建 base 必须是 `/app/<app>/`（**带结尾斜杠**），否则白屏。
> 出处：<https://leelaa.cn/p/adeofa.html>

### 5.6 打包

```bash
# 开发机（Linux/macOS）
chmod +x fnpack-1.2.3-linux-amd64 && sudo mv fnpack-1.2.3-linux-amd64 /usr/local/bin/fnpack
cd packaging/fnos/litv
fnpack build            # 产出 litv.fpk
```

Windows 下载的二进制**没有扩展名**，需重命名为 `fnpack.exe` 再 `.\fnpack.exe build`。
出处：<https://leelaa.cn/p/afppva.html>（实测记录）

### 5.7 安装与迭代

```bash
# 拷到 NAS 后
appcenter-cli install-fpk litv.fpk
# 或在设备上的源码目录直接装
appcenter-cli install-local
# 状态
appcenter-cli list && appcenter-cli start litv
```

---

## 六、账号体系接入（核心问题）

### 6.1 官方能力：统一网关注入身份 Header（推荐）

**机制**：应用在 `app/ui/config` 注册 `gatewayPrefix` + `gatewaySocket` → 用户访问 `/app/<appname>` → **飞牛网关先校验 NAS 用户会话** → 转发到 `${TRIM_APPDEST}/<socket>` → 请求头带上：

| Header | 含义 | 示例 |
|---|---|---|
| `X-Trim-Userid` | 当前用户 UID | `1000` |
| `X-Trim-Isadmin` | 是否管理员 | `true` / `false` |
| `X-Trim-Username` | 用户名 | `admin` |

- 官方定性：「这些值可以作为网关提供的可信身份上下文使用，但应用仍需要执行自己的业务鉴权」。
- **WebSocket 同样**：连接建立时也带这套身份上下文，"连接建立后，应将连接绑定到 `X-Trim-Userid`"。
- 官方明确警告：**不要信任客户端传入的用户 ID**，只用网关 Header。

出处：<https://developer.fnnas.com/docs/core-concepts/gateway-registration/>

**这就实现了"免登录"**：用户在飞牛桌面点开应用，网关已确认登录态，你的后台直接拿到 uid/username/isAdmin，不需要再弹一次登录框。

**官方还提供的能力（配合身份使用）**：
- `trim.file.checkUserACL`（`uid` + `path` → readable/writable/deletable）：按当前用户校验文件权限。
- `trim.file.getUserAccessibleFolders`（传 `uid`）：查该用户授权给应用的目录。
- `trim.file.convertPath`：`/vol1/...` → 用户可读的语义路径。
- 后端 API 统一入口：`POST /api/v1/trimapp`，走 Unix Socket `/var/run/trim_open_gateway_apiscope.socket`，`Authorization: Bearer $TRIM_API_TOKEN`。

出处：<https://developer.fnnas.com/docs/api/calling/>、<https://developer.fnnas.com/docs/api/authorization/file-acl/>

### 6.2 官方**没有**的东西（必须说清）

- 没有公开的「用户中心 / 账号」REST API（没有"列出所有 fnOS 用户""校验密码""签发 token"这类接口）。
- 没有 OAuth2 / OIDC Provider 文档，没有第三方应用注册 client_id/secret 的入口。
- 开放 API 自述为"系统能力开放的**第一期**，当前重点放在文件授权、页面路由、界面状态和后端查询等基础能力上，后续会继续探索更多系统能力的开放"——**登录/账号集成不在本期**。

出处：<https://developer.fnnas.com/api/overview/>、<https://developer.fnnas.com/docs/update-log/>

> 社区项目 `fnos-oidc-bridge` README 直接写明：「飞牛官方开发者文档明确"登录集成不在本期范围内"，本项目属社区 interop 研究，与官方无关联。」：<https://github.com/BeFortune/fnos-oidc-bridge>

### 6.3 三条可行路径（A/B/C）

#### 方案 A（推荐）：统一网关 Header 即身份，应用内建用户表

**做法**
- 应用走统一网关，中间件读 `X-Trim-Userid` / `X-Trim-Username` / `X-Trim-Isadmin`。
- 首次见到某 uid 时，在本地库 `users(uid, username, is_admin, first_seen, ...)` 里 upsert 一条。
- 业务权限（谁能管哪些频道、哪些终端）完全由你自己的表决定，`is_admin` 只用来放行"全局设置/系统级操作"。

**前提**
- 应用必须以统一网关模式提供 Web 入口（不能用端口直连模式，否则没有 Header）。
- `manifest.os_min_version` 要覆盖网关特性所需版本。

**优点**：官方支持、零破解、随 fnOS 升级不失效；改动量最小（一个中间件 + 一张表）。
**风险**
- 只在**经网关访问**时有身份。若你还暴露了独立端口/局域网直连，那条路径必须自己兜底鉴权（建议直接不暴露端口）。
- Header 可被伪造**仅当**有人能绕过网关直连你的 socket/端口——所以 socket 权限（`0660`）和"不监听 TCP"是安全边界。
- 拿不到用户邮箱/头像等资料（只有 uid/username/isAdmin）。

**对 LITV2 的落地建议**：这是主路径。IPTV 后台本来就是"管理员配置 + 终端观看"，用 `X-Trim-Isadmin` 区分"管理台"与"终端页"刚好；终端设备侧另发设备 token（不依赖 fnOS 身份）。

#### 方案 B（折中）：网关 Header + 可选的"绑定码"

**做法**：在 A 的基础上，为"非浏览器客户端"（电视盒子、手机 App）设计独立凭证：后台生成 6 位绑定码 → 终端首次输入 → 换取长期 device token。fnOS 身份只用于**管理台**，终端不参与 SSO。

**前提**：与 A 相同。
**优点**：绕开"网关只覆盖浏览器会话"的天然限制，且不碰 fnOS 内部实现。
**风险**：需要自己做 token 生命周期、撤销、限流；绑定码要防爆破。

**对 LITV2 的落地建议**：**A + B 组合**是最终形态——管理台免登录，终端用绑定码。

#### 方案 C（实验性，不建议作主路径）：自建 OIDC 桥接，把飞牛账号变成 IdP

**做法**：部署社区项目 `fnos-oidc-bridge`，它反向工程了 fnOS 内部 OAuth，把飞牛账号包装成标准 OIDC Provider（ES256/RS256、JWT、PKCE），供 Jellyfin/Immich/Portainer 等下游应用登录。

**已探明的内部链路**（社区逆向，非官方）：

```
浏览器 → /signin?client_id=...&redirect_uri=...        # 飞牛原生登录页
       → POST /oauthapi/authorize  {client_id, redirect_uri, response_type:code, state, token}
       → 302 回 redirect_uri?code=...
       → POST /v/api/v1/auth  {"source":"Trim-NAS","code":"..."}  → {token}
```

- `/oauthapi`、`/v1/accountapi` 由 nginx 转发到 `unix:/var/run/trim.accountsrv.sock`。
- OAuth 应用注册信息在 fnOS 的 **PostgreSQL `trim` 库 `oauth_app` 表**（官方影视/相册两条记录即在此）。
- `fnos-oidc-bridge` 0.5.0 起用 root 钩子在安装时自动往 `oauth_app` 插一条记录并回填 secret（`/etc/sudoers.d/` 放行一个 root 助手脚本）。

出处：
- <https://github.com/BeFortune/fnos-oidc-bridge>（架构图、安装、风险声明）
- <https://1328411791.github.io/2026/01/05/%E9%A3%9E%E7%89%9BOS-OAuth-%E7%99%BB%E5%BD%95%E8%A7%A3%E6%9E%90/>（原始逆向分析：nginx 配置、`trim.oauth_app`、`/oauthapi/authorize`、`/v/api/v1/auth` 请求响应体）

**前提**：root、SSH、愿意写 fnOS 内部数据库表。
**风险（项目作者自述 + 我们判断）**
- **实验性**：依赖未公开内部协议，**fnOS 升级可能直接失效**。
- 写系统数据库（`trim.oauth_app`）属于越界操作，卸载/升级可能留下脏数据。
- 桥接进程持有飞牛侧 token，是"高价值攻击目标"，**绝不能暴露公网**。
- 与我们"上架应用中心"的目标冲突：审核方对"改系统库 + 请求 root"的应用大概率不友好。

**对 LITV2 的判断**：**不做**。我们不需要"让别的应用用飞牛账号登录"，我们需要的是"我们的应用免登录"——方案 A 已经完全满足。C 只在未来要做"LITV 作为统一认证源接入其它自建服务"时才考虑，且应作为可选插件而非内核。

### 6.4 一句话对照

| 需求 | 用哪个 |
|---|---|
| 用户点开应用已登录 | A（网关 Header） |
| 知道谁是管理员 | A（`X-Trim-Isadmin`） |
| 按用户隔离数据/频道 | A（`uid` 做外键） |
| 电视/手机终端免密 | B（绑定码换 device token） |
| 让 Jellyfin 等用飞牛账号登录 | C（OIDC 桥接，实验性） |
| 读用户文件 | 官方开放 API（`api-scope` + `checkUserACL`） |

---

## 七、端口 / 反代 / WebSocket

### 7.1 三种访问模型（官方对照表）

| 能力 | `index.cgi` | 统一网关 | 端口服务 |
|---|---|---|---|
| 简单静态页面 | 适合 | 支持 | 支持 |
| 常驻服务 | 不推荐 | **适合** | 适合 |
| **WebSocket** | **不支持** | **支持** | 支持 |
| NAS 登录态 | 调用前校验 | **转发前校验 + 注入用户 Header** | 无（与登录态无关） |
| 性能 | 每请求起 CGI 进程 | 转发到常驻服务 | 直连 |
| 常见路径 | `/cgi/ThirdParty/{appname}/index.cgi/` | `/app/{appname}` | `http://host:port` |

出处：<https://developer.fnnas.com/docs/core-concepts/gateway-registration/>、<https://developer.fnnas.com/docs/core-concepts/index-cgi/>

**LITV2 选统一网关**：常驻服务 + 要 WebSocket + 要免登录，三条全中。

### 7.2 网关转发链路

```
浏览器 → https://<nas>/app/litv/... 
      → 飞牛网关（校验会话，注入 X-Trim-* Header）
      → unix:/var/apps/litv/target/app.sock
      → 你的 Go 服务
```

- Socket 固定位置：`/var/apps/{appname}/target/{gatewaySocket}`，脚本里用 `${TRIM_APPDEST}/{gatewaySocket}`。
- **Docker 应用也能用网关**：把 `${TRIM_APPDEST}` 挂进容器，让容器内服务在该目录建 socket。

### 7.3 必须自己做的三件事（否则 404/白屏）

1. **后端路由挂前缀**：`/app/litv/...` 原样进来，路由要能匹配（`gatewayPrefix` 与后端注册前缀必须一致）。
2. **前端 base 带结尾斜杠**：Vite `base: "/app/litv/"`（Vue Router 用 `import.meta.env.BASE_URL`，API 请求不要写死 `/api/...`）。
3. **启动前清旧 socket**：`rm -f "$SOCK"`，否则重启时 bind 失败。

出处：<https://leelaa.cn/p/adeofa.html>（含完整踩坑清单：白屏、404、socket 绑定失败、`/reader` 子路由失效、`/api/file` 少一层前缀）

### 7.4 WebSocket

- 复用同一前缀和同一 Socket，建议固定子路径：`/app/litv/ws`。
- 前端：

```js
const proto = location.protocol === "https:" ? "wss:" : "ws:";
const ws = new WebSocket(`${proto}//${location.host}/app/litv/ws`);
```

- 连接建立时同样带 `X-Trim-*` 身份上下文；**连接要绑定到 `X-Trim-Userid`**，不要相信 WS 消息里客户端自报的 uid。
- 官方未说明网关对 WS 的空闲超时、消息大小限制、是否需要 ping/pong 保活——**未找到公开资料**，建议客户端 30s 心跳 + 断线重连。

出处：<https://developer.fnnas.com/docs/core-concepts/gateway-registration/>

### 7.5 关于"fnOS 自带 nginx 反代"

- fnOS 内部确实用 nginx（`/usr/trim/nginx/conf/`），官方第一方应用的反代配置预置在此，多数请求以 Unix Socket 方式转发（社区逆向确认）：<https://1328411791.github.io/2026/01/05/%E9%A3%9E%E7%89%9BOS-OAuth-%E7%99%BB%E5%BD%95%E8%A7%A3%E6%9E%90/>
- 但**没有公开的"用户自定义反代规则"UI/API**——不要指望像群晖那样在界面里配 location。第三方应用获得子路径的正确方式就是**统一网关**。
- 需要公网/自定义域名/HTTPS 时，社区普遍另装 Nginx Proxy Manager / Lucky / Caddy（应用中心有现成包）。`fnos-oidc-bridge` 的 `docs/reverse-proxy.md` 给了 NPM/Caddy 的可用示例：<https://github.com/BeFortune/fnos-oidc-bridge>

---

## 八、N100 上 Docker 与原生 fpk 的差异 / 权限 / QSV 硬解

### 8.1 形态对比

| 维度 | 原生 fpk（Go 单文件） | Docker fpk（`--template docker`） |
|---|---|---|
| 打包 | 二进制直接放 `app/`，`fnpack build` | `app/docker/docker-compose.yaml` + `docker-project` 资源 |
| 运行身份 | `config/privilege` 的 `run-as` 控制（默认 `package` 非 root） | 容器内进程身份由镜像 `USER` 决定，**`privilege` 管不到** |
| 端口 | `service_port` 或走网关 | `ports: "${TRIM_SERVICE_PORT}:80"` |
| 架构 | `platform=x86`（N100 是 x86_64） | `platform=all` 也可以，但**镜像必须支持目标架构** |
| 体积/启动 | ~10-20MB 二进制，毫秒级启动 | 镜像拉取、层缓存，首次启动慢 |
| 依赖 | 零（Go 静态编译） | 需要设备上有 docker + 能拉镜像 |
| 升级 | 换二进制即可 | 换 tag，注意数据卷 |
| 调试 | 直接看 `$TRIM_PKGVAR/*.log` | `docker logs`，且 `cmd/main status` 要 `docker inspect` 判活 |
| 官方定位 | 「Native 应用案例」 | 「Docker 应用案例」 |

出处：<https://developer.fnnas.com/docs/examples/native/>、<https://developer.fnnas.com/docs/examples/docker/>

### 8.2 是否 root？

- **默认不是 root**：`run-as: package` 用专用应用用户运行。
- 官方明确不鼓励 root：「以 root 身份运行应用，会放大 Web 处理逻辑、API、后台任务和第三方依赖中的安全风险」；只在"生命周期脚本确实需要特权准备任务"时用 root，且长期服务应 `runuser` 降权。
- 生产包 `IamAyang233/fnos-openvpn` 的实践佐证：为创建 TUN 设备/加载 nft 规则用 root 起，但 **Web 后端降权 nobody**，特权操作走 sudoers 白名单助手脚本；其 changelog 明确写"上架要求：长期运行并对外提供访问的进程尽可能非 root"。
  出处：<https://github.com/IamAyang233/fnos-openvpn>

> 对 LITV2：**不需要 root**。Go 服务跑 `run-as: package`，只读媒体目录用 `data-share` + 用户授权即可。

### 8.3 QSV / 硬解（N100 = Alder Lake-N，UHD Graphics）

**官方给的路子**：`config/privilege` 里加 `"join-groups": ["video", "render"]`。官方原文："需要访问视频设备、GPU 渲染或硬件加速媒体输出的应用，可能需要通过 `video` 或 `render` 等系统用户组获得对应访问能力"。
出处：<https://developer.fnnas.com/docs/core-concepts/privilege/>

**实践要点（部分为社区经验，标注状态）**：
- 设备节点 `/dev/dri/renderD128`（QSV/VAAPI 走 render node，权限由 `render` 组控制），`/dev/dri/card0` 由 `video` 组控制。→ 两个组都要加。
- 需要系统里有 Intel 媒体驱动（`iHD_drv_video.so` / `intel-media-driver`）。社区有反馈 fnOS 曾在内核升级后支持 N150 核显转码，也有人在应用中心装 i915 驱动包：
  <https://v2ex.com/t/1119960>
- ffmpeg 命令形态（社区通用，非 fnOS 官方文档）：`ffmpeg -hwaccel qsv -hwaccel_output_format qsv -i in -c:v h264_qsv ...`，或用 VAAPI：`-vaapi_device /dev/dri/renderD128 -vf 'format=nv12,hwupload' -c:v h264_vaapi`。
- **未证实**：fnOS 是否随系统自带 `ffmpeg`（含 QSV 编译选项）、其路径与版本。**建议 LITV2 自带静态 ffmpeg 二进制**（放 `app/bin/`），只依赖 `/dev/dri` 与 `render`/`video` 组，避免踩发行版 ffmpeg 缺 QSV 的坑。Jellyfin 的 Intel HWA 文档可作为参数参考：<https://jellyfin.org/docs/general/post-install/transcoding/hardware-acceleration/intel/>

**Docker 侧对照**：需要 `devices: ["/dev/dri:/dev/dri"]` 或 `device_cgroup_rules`，并确保容器内用户属于 `render`/`video` 组。N100 上社区常见做法即挂 `/dev/dri`：
<https://github.com/RROrg/rr/issues/4391>

### 8.4 结论：LITV2 选原生 fpk

理由：① Go 静态二进制零依赖，最契合 fpk 的"target 目录放二进制 + cmd/main 起进程"模型；② 免登录依赖统一网关的 Header，原生模式下 socket/路由完全可控；③ 不需要 docker 层，省内存（N100 通常 8-16G，但 NAS 上还跑着影视/相册）；④ 硬解只要 `join-groups` 两行配置；⑤ 上架审核对"非 root + 无系统侵入"更友好。

---

## 九、参考链接清单（访问日期 2026-11-01）

### 官方

| 内容 | URL |
|---|---|
| 开发者平台首页 | <https://developer.fnnas.com/> |
| 欢迎加入 / 平台定位与体量 | <https://developer.fnnas.com/docs/guide/> |
| 全量文档快照（AI 友好） | <https://developer.fnnas.com/llms-full.txt> |
| 文档索引 | <https://developer.fnnas.com/llms.txt> |
| 更新日志（版本变迁、字段废弃） | <https://developer.fnnas.com/docs/update-log/> |
| 准备工作（测试设备/CLI） | <https://developer.fnnas.com/docs/quick-started/prerequisites/> |
| 创建应用（HelloFnos 最小包） | <https://developer.fnnas.com/docs/quick-started/create-application/> |
| 测试应用（安装/CLI） | <https://developer.fnnas.com/docs/quick-started/test-application/> |
| 上架应用 | <https://developer.fnnas.com/docs/quick-started/publish-application/> |
| 应用框架（目录/生命周期/退出码） | <https://developer.fnnas.com/docs/core-concepts/framework/> |
| Manifest 字段全表 | <https://developer.fnnas.com/docs/core-concepts/manifest/> |
| 环境变量全表 | <https://developer.fnnas.com/docs/core-concepts/environment-variables/> |
| 应用权限（run-as / join-groups / root） | <https://developer.fnnas.com/docs/core-concepts/privilege/> |
| 应用资源（data-share / linker / docker） | <https://developer.fnnas.com/docs/core-concepts/resource/> |
| 应用入口（.url 字段） | <https://developer.fnnas.com/docs/core-concepts/app-entry/> |
| index.cgi | <https://developer.fnnas.com/docs/core-concepts/index-cgi/> |
| **统一网关（前缀/Socket/身份 Header/WS）** | <https://developer.fnnas.com/docs/core-concepts/gateway-registration/> |
| 用户向导（wizard 表单 schema） | <https://developer.fnnas.com/docs/core-concepts/wizard/> |
| 应用依赖（install_dep_apps） | <https://developer.fnnas.com/docs/core-concepts/dependency/> |
| 中间件服务（redis/minio/rabbitmq） | <https://developer.fnnas.com/docs/core-concepts/middleware/> |
| 运行时环境（python/node/java） | <https://developer.fnnas.com/docs/core-concepts/runtime/> |
| Native 应用案例（Notepad，含 socket/前缀/数据目录） | <https://developer.fnnas.com/docs/examples/native/> |
| Docker 应用案例（hello-docker） | <https://developer.fnnas.com/docs/examples/docker/> |
| fnpack（下载 + 结构 + 校验） | <https://developer.fnnas.com/docs/cli/fnpack/> |
| appcenter-cli | <https://developer.fnnas.com/docs/cli/appcentercli/> |
| 开放 API 概述 | <https://developer.fnnas.com/api/overview/> |
| 开放 API 调用方式（api-scope/JS SDK/后端 API） | <https://developer.fnnas.com/api/calling/> |
| 授权与文件概览 | <https://developer.fnnas.com/api/authorization/overview/> |
| 文件权限检查 checkUserACL | <https://developer.fnnas.com/api/authorization/file-acl/> |
| 页面路由 | <https://developer.fnnas.com/api/page/routing/> |
| 错误码 | <https://developer.fnnas.com/api/error-codes/> |
| fnpack 下载直链（Linux x86） | <https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64> |
| fnpack 下载直链（Windows x86） | <https://static2.fnnas.com/fnpack/fnpack-1.2.3-windows-amd64> |
| 系统镜像下载 | <https://www.fnnas.com/download?key=fnos> |
| 官方帮助中心 | <https://help.fnnas.com/> |

### 社区（实测/逆向/示例）

| 内容 | URL |
|---|---|
| 手把手写飞牛应用：创建 & 打包（fnpack 实操、Windows 无扩展名坑） | <https://leelaa.cn/p/afppva.html> |
| **在飞牛中使用统一网关（完整踩坑清单）** | <https://leelaa.cn/p/adeofa.html> |
| fnOS 应用提取器（.fpk 就是 tar.gz、checksum=md5(app.tgz)） | <https://github.com/cjm2004/fnos-fpk-extractor> |
| fnos-apps：175 款第三方 .fpk 打包仓库（含通用 `build-fpk.sh`） | <https://github.com/conversun/fnos-apps> |
| 同上，通用打包脚本原文 | <https://github.com/conversun/fnos-apps/blob/main/scripts/build-fpk.sh> |
| **fnos-openvpn：真实生产包 manifest / 权限降权实践** | <https://github.com/IamAyang233/fnos-openvpn> |
| **fnos-oidc-bridge：把 fnOS 账号变成 OIDC IdP（实验性）** | <https://github.com/BeFortune/fnos-oidc-bridge> |
| 飞牛OS OAuth 登录解析（逆向：nginx/oauth_app/接口） | <https://1328411791.github.io/2026/01/05/%E9%A3%9E%E7%89%9BOS-OAuth-%E7%99%BB%E5%BD%95%E8%A7%A3%E6%9E%90/> |
| motrix-fnos（Go/Node 混合、统一网关） | <https://github.com/RockerHX/motrix-fnos> |
| fnmedia-ext（fnOS FPK 影视扩展） | <https://github.com/yujian0815/fnmedia-ext> |
| deepseek-harness-fpk（GitHub Actions 自动编译发布 fpk） | <https://github.com/10000ge10000/deepseek-harness-fpk> |
| FnDepot（第三方应用子仓库） | <https://github.com/710850609/FnDepot> |
| moo（第三方应用中心，三源同步） | <https://github.com/Blue-Mink/moo> |
| fnOS 0.9.2 内核升级至 6.12.18（IT之家） | <https://www.ithome.com/0/851/973.htm> |
| fnOS 基于 Debian 构建（VMware 安装实录） | <https://charles7c.top/posts/2025/12/install-fnos-with-vmware.html> |
| 飞牛 N150 核显转码/i915 驱动讨论 | <https://v2ex.com/t/1119960> |
| Jellyfin Intel QSV/VAAPI 参数参考 | <https://jellyfin.org/docs/general/post-install/transcoding/hardware-acceleration/intel/> |
| 官方论坛 | <https://club.fnnas.com/> |

---

## 十、未解之谜 / 需要确认的点

### 10.1 技术缺口（查不到公开资料）

1. **`.fpk` 是否有签名机制**。`checksum = md5(app.tgz)` 是明文的、可重算的——只能防传输损坏，不能防篡改。是否另有签名校验、应用中心上架时是否做服务端签名，**未找到公开资料**。安全上应按"无签名"设计（即假设本地 fpk 可被任意重打包）。
2. **`.sc` 端口转发文件**的格式与语义。社区包根会带 `*.sc`（打包脚本 `cp "$APP_DIR"/fnos/*.sc`），官方文档零提及。**未证实**。我们不需要它（走网关）。
3. **统一网关的请求限制**：请求体大小上限、超时、并发、WS 空闲超时、是否缓冲流式响应（SSE/大文件 Range）。官方文档只讲路由与鉴权，**这些一概未写**。IPTV 场景涉及 m3u8/TS 流式转发，**必须实测**。
4. **网关是否透传/覆盖自定义 Header**（如 `Authorization`、`Range`、`X-Forwarded-*`）。官方只列了注入的三个 `X-Trim-*`，其余未说明。
5. **Socket 权限的正确值**。社区用 `0660` 且"应用用户 + 用户组"运行；网关进程以什么身份访问该 socket 未文档化。若网关不在同组，可能需要 `0666` 或调整属组——**需实测**。
6. **`os_min_version` 的准确取值**。官方 Native 示例与生产包都用 `1.1.3100`；开放 API 要求 `1.2.0401` + App `1.34.0`。我们只用网关（不用开放 API），取 `1.1.3100` 应该够，但**建议在目标机 `$TRIM_SYS_VERSION` 上核对**。
7. **`platform` 与 `arch` 的兼容期**。`arch` 已废弃，但老版本 fnOS 是否仍读 `arch`？若目标机是较老版本，可能需要两个都写（**未证实**）。
8. **`wizard/` 表单的完整 schema**（`text/password/radio/checkbox/select/switch/tips` + `rules: required/min/max/len/pattern` 已知），但**条件显示、字段联动、默认值来源**（如自动列可用端口）未文档化。
9. **应用中心对 `service_port` 的强依赖**。官方 Native 网关示例省略了它，但社区打包脚本 `require_manifest_key "service_port"` 强制要求——说明某些工具链/版本会读它。**走网关时省略是否 100% 安全，需实测**。
10. **多用户场景下"应用是否对普通用户可见"**。`allUsers: true` 让入口对所有 fnOS 用户可见，但应用自身的"管理员/普通用户"边界要自己实现（网关只给 `isAdmin` 布尔值）。

### 10.2 需要向 lead / 用户确认的产品决策

1. **是否必须上架应用中心？** 若只自用，`appcenter-cli install-fpk` / 手动安装即可，能省掉截图、审核、root 使用说明等一堆事。上架则需要走"开发者先锋交流群"人工流程（开发者后台仍 Coming Soon）。
2. **目标 fnOS 版本范围**。决定 `os_min_version`、是否可用开放 API（`1.2.0401+`）、以及 `platform`/`arch` 双写与否。**建议 lead 提供目标机 `$TRIM_SYS_VERSION` 与 `$TRIM_KERNEL_VERSION` 实测值**。
3. **是否需要"非网关"的访问方式**（局域网直连 `http://nas:端口`、或给电视盒子用的裸 API）。若要，就必须自建鉴权，且**不能依赖 `X-Trim-*` Header**——这是架构级分叉点。
4. **终端（电视/手机）如何认证**。fnOS 的 SSO 只覆盖浏览器会话；IPTV 终端必须有独立凭证方案（绑定码 / device token / 直接 URL 带 token）。**这条不确认，SSO 方案无法收口**。
5. **是否需要硬解转码**。若 LITV2 只做"频道编排 + 转封装/直通"，不需要 QSV，就不用加 `join-groups`，权限面更小；若要做实时转码，需确认 N100 的核显在目标机上确实可用（`ls /dev/dri` + 自带 ffmpeg 试跑）。
6. **数据放哪**：`$TRIM_PKGVAR`（对用户不可见，适合 db/缓存）还是 `data-share`（文件管理器可见，适合录像/播放列表）。建议**两者都用**：db 在 PKGVAR，录像与导出在 `litv/recordings`。
7. **是否接受"应用随 fnOS 升级而失效"的风险**做方案 C。默认建议：**不做**。
