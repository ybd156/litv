# LITV 管理台（admin/）

Vue 3 + Vite + TypeScript + Naive UI 的 Web 管理台，构建产物 `admin/dist/` 会被 `go:embed` 打进 `litvd` 二进制，
由服务端挂在飞牛统一网关的 `/app/litv/` 下提供。

## 快速开始

```bash
cd admin
npm install --registry=https://registry.npmmirror.com   # 见下方「关于 registry」
npm run dev          # 开发模式，接口走 vite proxy -> http://127.0.0.1:9527
npm run dev:mock     # 纯前端预览（不需要服务端，全部走假数据）
npm run build        # 类型检查 + 生产构建 -> dist/
npm run build:mock   # 带假数据的构建，用来离线验证界面
npm run preview      # 本地静态预览 dist/
```

打开 <http://localhost:5273/app/litv/>（注意结尾斜杠）。

## 关于 registry

本机 `npm install` 首次用的是官方源，超时后改用镜像并成功：

```
npm install --registry=https://registry.npmmirror.com
# added 75 packages in 2m
```

`package-lock.json` 里记录的是 `registry.npmmirror.com` 的地址。如果换到能直连官方源的机器，删掉
`package-lock.json` 重装即可。

## 部署约束（踩错就白屏，别改）

| 约束 | 实现位置 | 原因 |
|---|---|---|
| 子路径 `/app/litv/`，结尾带斜杠 | `vite.config.ts` 的 `base: '/app/litv/'` | 飞牛网关按前缀转发，base 少了斜杠会 404 |
| 路由用 hash 模式 | `src/router/index.ts` 的 `createWebHashHistory()` | history 模式下深链接刷新会被网关兜底成 404 |
| API baseURL 用**相对路径** `api/v1` | `src/api/http.ts` 的 `apiUrl()` | 写死 `/api/v1` 会打到网关根路径上 404 |

`apiUrl()` 的细节：hash 路由下 `document` 目录恒为 `/app/litv/`，相对路径 `api/v1/xxx` 会自然拼成
`/app/litv/api/v1/xxx`；只有当页面不在 base 目录下（例如手工放到别的路径）时才回退到 `BASE_URL + 'api/v1/'`。

## 目录结构

```
src/
├─ api/
│  ├─ http.ts            # fetch 封装：统一拆 {code,msg,data}、错误码→中文、2001 回调
│  ├─ errors.ts          # 错误码表 + ApiError
│  ├─ types.ts           # 与 01-API.md 对齐的实体类型（snake_case）
│  ├─ normalize.ts       # 数组/{items:[]}/{lines:[]} 归一化
│  ├─ modules/           # sources / channels / epg / devices / system 五个领域
│  └─ mock/              # VITE_MOCK=1 时的假数据 + 假路由
├─ composables/          # useAsyncData（三态加载）、useDragSort（原生 HTML5 拖拽）
├─ components/           # 图标、三态块、表头、弹窗、抽屉、面板（按功能拆）
├─ layouts/AdminLayout.vue
├─ views/                # Dashboard / Sources / Channels / Epg / Devices / Settings / NotAuthorized
├─ theme/                # darkTheme 覆盖（主色 #1FA2C0）
├─ utils/                # 时间格式化、toast/confirm 封装
├─ session.ts            # GET system/me 拿身份，决定进管理台还是「请从飞牛桌面打开」
└─ router/index.ts
```

## 鉴权

不设登录页。启动时调 `GET api/v1/system/me`：

- 成功 → 进管理台，右上角显示用户名 / 管理员标签
- 返回 `2001`（或 HTTP 401）→ 显示「请从飞牛桌面打开本应用」说明页
- 其他错误（连不上 / 网关返回 HTML）→ 显示错误页 + 重试

请求过程中任何接口返回 2001，也会立刻切到说明页（`setUnauthorizedHandler`）。

## Mock 模式

`VITE_MOCK=1`（`npm run dev:mock` / `npm run build:mock`）时所有请求走 `src/api/mock/`，数据在内存里，刷新即还原。

地址栏参数可以覆盖：

| 参数 | 效果 |
|---|---|
| `?mock=1` | 强制用假数据（生产构建也能用，方便现场演示） |
| `?mock=unauth` | 让 `system/me` 返回 2001，用来预览「请从飞牛桌面打开」那一页 |

假数据覆盖：4 个源（含 1 个拉取失败、1 个停用、1 个从没拉过）、51 个频道 / 6 个分组、每频道 1~3 条线路、
30 个 EPG 频道（含未匹配）、5 台设备（含 1 台待批准）、200+ 行日志、系统配置与版本信息。

## 体积（`npm run build` 实测）

| 文件 | 原始 | gzip |
|---|---|---|
| index.html | 0.69 kB | 0.37 kB |
| assets/style-*.css | 19.47 kB | 4.51 kB |
| assets/vendor-*.js（vue + router） | 106.86 kB | 41.60 kB |
| assets/ui-*.js（naive-ui） | 673.06 kB | 186.08 kB |
| assets/index-*.js（业务入口） | 36.12 kB | 14.23 kB |
| 6 个页面 chunk | 合计约 67 kB | 合计约 24 kB |
| **合计** | **约 920 kB** | **约 262 kB**（预算 400 kB） |

没引图表库、没引 `@vicons`（图标是自写的 24×24 SVG path），拖拽用原生 HTML5 而不是 sortablejs。

## 页面清单

| 路由 | 页面 | 主要能力 |
|---|---|---|
| `#/` | 概览 | 在线终端 / 频道总数 / 今日起播次数 / 失效源 四个指标 + 最近活动时间线 + 服务状态 |
| `#/sources` | 源管理 | 列表、新增/编辑/删除、立即刷新、**预览前 50 条**（标脏数据）、m3u/txt/tvbox/json |
| `#/channels` | 频道管理 | 分组侧栏、搜索、分页、**拖拽排序**（含上移/下移兜底）、列可隐藏、批量启用/停用/改分组/删除、行内展开多线路增删、台标缩略图 |
| `#/epg` | EPG 管理 | EPG 源列表/新增/删除/全部刷新；「频道 ↔ EPG」映射（左拖右，或点选后点目标） |
| `#/devices` | 终端设备 | 设备列表、待批准审批、生成 6 位绑定码（大字 + 倒计时）、远程配置覆盖、吊销 |
| `#/settings` | 系统设置 | 端口 / 并发上限 / 转码 / 日志级别 / EPG 刷新时间 / 局域网访问、版本信息、日志 200 行 |

## 验收记录（实际执行）

### 1. 构建

```bash
$ cd admin && npm install --registry=https://registry.npmmirror.com   # added 75 packages in 2m
$ npm run build                                                        # vue-tsc --noEmit && vite build
✓ 2762 modules transformed.
dist/index.html                          0.69 kB │ gzip:   0.37 kB
dist/assets/style-C8R0fTGW.css          19.54 kB │ gzip:   4.52 kB
dist/assets/devices-DoVPddKw.js          0.48 kB │ gzip:   0.24 kB
dist/assets/sources-d7WZyMkF.js          0.60 kB │ gzip:   0.32 kB
dist/assets/channels-_wJE6tkV.js         0.97 kB │ gzip:   0.46 kB
dist/assets/format-BaGDHYRF.js           1.48 kB │ gzip:   0.82 kB
dist/assets/StateBlock-qdl0Ht0p.js       1.83 kB │ gzip:   0.96 kB
dist/assets/DashboardView-CGQatuE-.js    6.86 kB │ gzip:   3.18 kB
dist/assets/SettingsView-B9M_ylbc.js     8.23 kB │ gzip:   3.63 kB
dist/assets/SourcesView-Jq4arZMu.js     11.23 kB │ gzip:   4.66 kB
dist/assets/EpgView-D70YD7tK.js         11.43 kB │ gzip:   4.68 kB
dist/assets/DevicesView-Bi-VvnEr.js     11.50 kB │ gzip:   4.90 kB
dist/assets/ChannelsView-O4DlYfQt.js    17.72 kB │ gzip:   6.90 kB
dist/assets/index-Yzw4Jq9q.js           36.31 kB │ gzip:  14.33 kB
dist/assets/vendor-UP856KlM.js         106.86 kB │ gzip:  41.60 kB
dist/assets/ui-C0pJ1cHt.js             673.06 kB │ gzip: 186.08 kB
✓ built in 24.83s
```

零报错、零 warning（`chunkSizeWarningLimit` 已按 naive-ui 单块体积设定）。`dist/` 共 922 KB，
全量 gzip 单流 **268,079 B ≈ 262 KB**，分文件相加约 278 KB，都在 400 KB 预算内。

### 2. 静态服务 + curl

```bash
$ npx vite preview --outDir dist --port 4174
$ curl -sS -o /dev/null -w "%{http_code}\n" http://127.0.0.1:4174/app/litv/
200
$ curl -sS http://127.0.0.1:4174/app/litv/ | grep -o '/app/litv/assets/[A-Za-z0-9_.-]*'
/app/litv/assets/index-Yzw4Jq9q.js
/app/litv/assets/vendor-UP856KlM.js
/app/litv/assets/ui-C0pJ1cHt.js
/app/litv/assets/style-C8R0fTGW.css
# 四个资源全部 200
```

`index.html` 里所有资源路径都带 `/app/litv/` 前缀，子路径部署没问题。

### 3. 页面渲染 / 交互（headless Chrome 实测）

用 `chrome --headless=new --dump-dom` 和 CDP 驱动真实点击，在 `?mock=1` 下逐页验证：

| 检查 | 结果 |
|---|---|
| `#/` 概览：4 张指标卡 + 最近活动 + 服务状态 | PASS |
| `#/sources` 列表 + 「添加源」弹窗 → 填表保存 → 新源入列表 + toast | PASS |
| `#/sources` 删除前弹出二次确认 | PASS |
| `#/channels` 列表/分组侧栏/分页（共 51 个频道）/台标缩略图 | PASS |
| `#/channels` 下移排序 → 调 reorder 接口 → toast「顺序已保存」→ 行序变化 | PASS |
| `#/channels` 展开行 → 线路面板（含「加线路」输入框） | PASS |
| `#/channels` 勾选两行 → 批量操作条「已选 2 个」 | PASS |
| `#/epg` EPG 源列表 / 刷新 / 新增 | PASS |
| `#/epg?tab=mapping` 点选左频道 + 点右侧 EPG → toast「已对应到…」 | PASS |
| `#/devices` 生成绑定码弹窗：6 位大字 + 倒计时「有效期还剩 4:59」 | PASS |
| `#/devices` 待批准卡片 + 批准/拒绝 | PASS |
| `#/settings` 运行配置 / 版本信息 / 日志 200 行 | PASS |
| `?mock=unauth` → 「请从飞牛桌面打开本应用」说明页 | PASS |
| 不带 mock 的真实构建（无服务端）→ 「没连上 LITV 服务」+ 重试，不白屏 | PASS |

截图见 `admin/screenshots/`（dashboard / sources / channels / epg / epg-mapping / devices / settings / 绑定码弹窗 / 二次确认）。

### 4. 只靠代码评审、没做自动化交互的部分

- 真实鼠标拖拽行排序（HTML5 `dragstart/dragover/drop`）：自动化里用等价的「下移」按钮走了同一条 reorder 链路，
  拖拽本身没有在浏览器里真实拖过，需要在真机上手动点一遍确认手感。
- 源「预览前 50 条」抽屉、编辑类弹窗的字段回填：逻辑简单，未逐个自动化。

## 对 API 契约的疑问（已按最合理方式先实现，服务端可据此调整）

1. **`GET /system/me` 不在 01-API.md 里**。前端需要一个「我是谁」的接口来决定进管理台还是显示说明页，
   文档只写了网关注入 `X-Trim-Userid` 等头。建议正式补进契约：`GET /api/v1/system/me → {uid, username, is_admin}`，
   未认证返回 `2001`。
2. **概览的「今日起播次数」没有数据来源**。现在读 `GET /system/info` 的可选字段 `plays_today`，
   没拿到就显示「—」并在页面上说明。建议 `system/info` 一并返回 `channels_total / devices_online / sources_failed / plays_today`，
   或者单独给 `GET /system/stats`。
3. **「最近活动」没有接口**。当前用「源的 `last_fetch_at` + 设备的 `last_heartbeat_at`」在前端合成时间线。
   如果要做真的活动流，建议 `GET /system/events?limit=20`。
4. **EPG 映射缺一个接口**。做「频道 ↔ EPG 频道」界面必须有「EPG 里有哪些频道」，文档里没有。
   前端按 `GET /api/v1/epg/channels → [{id,name,source,program_count}]` 对接；服务端没实现时页面降级
   （用已匹配的 `epg_id` 反推右侧列表）并在界面上提示。
5. **`POST /channels/reorder {ids}` 的语义**。分页 + 分组筛选下，前端只能拿到当前页的 id。
   现在提交「当前页的新顺序」，请服务端按「这些 id 按此相对顺序重排，其余保持不动」实现（mock 就是这么做的）。
   如果服务端期望**全量** id 列表，需要额外约定。
6. **`GET /sources/{id}/preview` 的返回结构未定义**。前端兼容 `[{name,group,url,tvg_id,logo}]` 与
   `{items:[...], total, truncated}` 两种。建议固定为后者。
7. **`GET /system/logs` 的返回结构未定义**。前端兼容纯文本、`string[]`、`{lines:[]}`。建议固定 `{lines:string[]}`。
8. **`GET /devices` 的字段未定义**。前端用了 `{id,name,platform,model,online,last_heartbeat_at,status,ip,app_version,config}`，
   其中 `status` 取 `bound|pending|revoked`；`POST /devices/bindcode` 期望 `{code, expires_in}`（缺省按 300 秒倒计时）。
9. **`GET /device/pending` 标注在「公开（无鉴权）」段，但又写「需 admin」**，两处矛盾，建议挪到管理台段并明确需要 admin。
10. **`PUT /devices/{id}/config`** 的字段前端按 `{decode:'hard'|'soft'|'auto', buffer_ms:number, aspect:'auto'|'16:9'|'4:3'|'fill', autoplay:boolean}` 提交。
11. **`PUT /system/config`** 前端按 `{port, max_concurrent, transcode_enabled, log_level, epg_refresh_hour, allow_lan}` 提交（后三项为可选）。
12. `GET /channels` 的 `group` 参数：前端传**分组名**（与 `GET /groups` 返回的 `name` 一致）。如果服务端用 group id，需要改。
