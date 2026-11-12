# 01 · HTTP API 契约

> 本文件的**路由表与错误码表由脚本从源码机械抽取**，不是手写。
> 生成源：`server/internal/api/api.go`（路由）、`server/internal/api/*.go`（错误码）。
> 每行都带源码行号，可直接回溯核对。改动 API 后重跑生成即可，禁止手工维护表格。

## 一、信封

所有 JSON 接口返回同一形状，**没有例外**：

```json
{ "code": 0, "msg": "ok", "data": {} }
```

- 成功：`code = 0`。`ok()` 走 HTTP 200，`okStatus()` 走其它成功状态码（目前只有 202 Accepted）。
- 失败：`code != 0`，`msg` 为面向人的说明，`data` 省略。
- **前端只看 `code`，不看 HTTP 状态码**；但 curl / 代理 / 日志只认状态码，所以两者必须同时正确
  （见 [api.go:582-587](../../server/internal/api/api.go#L582-L587) 的注释）。
- 请求体上限 1 MiB（`decode()` 里的 `io.LimitReader`）。

## 二、错误码

代码是**语义分桶**，不是一一对应：同一个 `code` 会覆盖同一类失败，`msg` 才是具体原因。

| code | 出现次数 | 代表消息 |
|---|---|---|
| `1001` | 70 | 文件是空的 |
| `1002` | 1 | 该设备标识已被其他平台占用，请重新安装或联系管理员清理旧设备 |
| `2001` | 3 | 密码错误 |
| `2002` | 9 | 需要管理员权限 |
| `2003` | 14 | 设备未绑定 |
| `2004` | 4 | 绑定码不存在 |
| `2005` | 3 | 上报过于频繁 |
| `3001` | 22 | 源不存在 |
| `3002` | 7 | 该频道没有可用线路 |
| `3003` | 2 | 录制文件是符号链接，已保留记录 |
| `4001` | 3 | 拉取失败:  |
| `5001` | 18 | 录制未启用 |
| `5002` | 2 | 投屏失败:  |

约定：

- `1001` 参数非法（缺字段、越界、格式不对）——占绝大多数，说明 HTTP 边界校验是逐字段做的。
- `2001` 未认证（无管理员会话、无身份头）**以及**密码错误。两者共用此码，`msg` 才区分得开。
- `2002` 已认证但无权限 **或** 签名地址无效/过期。**这两件事共用同一个码**，客户端不能靠它区分。
- `2003` 设备未认证（未绑定、token 失效、pending 未批准）。
- `2004` 限流与绑定码问题（登录限流、绑定码已用/过期/不匹配）。
- HTTP 语义仍按 AGENTS §6：**401 = 未认证，403 = 已认证但无权限，不得混用**；`code` 是业务语义，不与状态码一一对应。
- `5001` 服务端内部/配置类失败（写文件、ffmpeg 缺失、目录创建）。

## 三、认证三档

| 档位 | 中间件 | 依据 | 覆盖路由 |
|---|---|---|---|
| 公开 | 无 | — | 12 条 |
| 管理台 | `requireAdmin` | 管理员会话 / 网关身份 | 59 条 |
| 终端 | `requireDevice` | 设备 bearer token | 21 条 |

要点（与 AGENTS §3 对应）：

- `X-Trim-*` 身份头**只对确认来自网关的请求可信**。`Handler(viaGateway bool)` 用这个参数决定
  是否信任网关头；TCP 入口传 `false`，带了头也不会变成管理员。
- 原始 socket 对端必须在 `middleware.RealIP` **之前**捕获（[api.go:296-300](../../server/internal/api/api.go#L296-L300)），
  否则 `X-Forwarded-For` 能伪造来源。
- `/live/*` 是公开路径，但靠 **HMAC 签名 + 过期时间**保护，不是匿名可取。
- `/dlna` 是 `chi.Mount` + `StripPrefix` 挂载的独立 handler，不经过上面的中间件链。

## 四、路由总表（92 条）

### 一、公开入口（无身份中间件）

共 12 条。

| 方法 | 路径 | 处理器 | 源码位置 |
|---|---|---|---|
| `GET` | `/api/v1/health` | `a.health` | [api.go:304](../../server/internal/api/api.go#L304) |
| `POST` | `/api/v1/device/register` | `a.deviceRegister` | [api.go:305](../../server/internal/api/api.go#L305) |
| `POST` | `/api/v1/auth/login` | `a.passwordLogin` | [api.go:306](../../server/internal/api/api.go#L306) |
| `POST` | `/api/v1/auth/logout` | `a.passwordLogout` | [api.go:309](../../server/internal/api/api.go#L309) |
| `GET` | `/live/{id}/play` | `a.livePlay` | [api.go:433](../../server/internal/api/api.go#L433) |
| `GET` | `/live/{id}/index.m3u8` | `a.liveManifest` | [api.go:434](../../server/internal/api/api.go#L434) |
| `GET` | `/live/{id}/stream.ts` | `a.liveTS` | [api.go:435](../../server/internal/api/api.go#L435) |
| `GET` | `/live/{id}/timeshift.ts` | `a.liveTimeshift` | [api.go:436](../../server/internal/api/api.go#L436) |
| `GET` | `/live/{id}/remux.m3u8` | `a.liveRemuxPlaylist` | [api.go:438](../../server/internal/api/api.go#L438) |
| `GET` | `/live/{id}/remux/{seg}` | `a.liveRemuxSegment` | [api.go:439](../../server/internal/api/api.go#L439) |
| `GET` | `/live/{id}/seg` | `a.liveSegment` | [api.go:440](../../server/internal/api/api.go#L440) |
| `GET` | `/logo/{id}` | `a.serveLogo` | [api.go:443](../../server/internal/api/api.go#L443) |

### 二、管理台（`requireAdmin`）

共 59 条。

| 方法 | 路径 | 处理器 | 源码位置 |
|---|---|---|---|
| `GET` | `/api/v1/system/me` | `a.systemMe` | [api.go:315](../../server/internal/api/api.go#L315) |
| `GET` | `/api/v1/system/info` | `a.systemInfo` | [api.go:316](../../server/internal/api/api.go#L316) |
| `GET` | `/api/v1/system/logs` | `a.systemLogs` | [api.go:317](../../server/internal/api/api.go#L317) |
| `GET` | `/api/v1/system/config` | `a.getConfig` | [api.go:318](../../server/internal/api/api.go#L318) |
| `PUT` | `/api/v1/system/config` | `a.putConfig` | [api.go:319](../../server/internal/api/api.go#L319) |
| `GET` | `/api/v1/system/streams` | `a.activeStreams` | [api.go:320](../../server/internal/api/api.go#L320) |
| `POST` | `/api/v1/system/probe` | `a.runProbe` | [api.go:321](../../server/internal/api/api.go#L321) |
| `GET` | `/api/v1/stats/overview` | `a.statsOverview` | [api.go:323](../../server/internal/api/api.go#L323) |
| `GET` | `/api/v1/stats/channels` | `a.statsChannels` | [api.go:324](../../server/internal/api/api.go#L324) |
| `GET` | `/api/v1/alerts/dead-channels` | `a.deadChannels` | [api.go:326](../../server/internal/api/api.go#L326) |
| `GET` | `/api/v1/web/play/{id}` | `a.webPlay` | [api.go:328](../../server/internal/api/api.go#L328) |
| `GET` | `/api/v1/recordings` | `a.listRecordings` | [api.go:331](../../server/internal/api/api.go#L331) |
| `POST` | `/api/v1/recordings` | `a.createRecording` | [api.go:332](../../server/internal/api/api.go#L332) |
| `DELETE` | `/api/v1/recordings/{id}` | `a.deleteRecording` | [api.go:333](../../server/internal/api/api.go#L333) |
| `POST` | `/api/v1/recordings/{id}/stop` | `a.stopRecording` | [api.go:334](../../server/internal/api/api.go#L334) |
| `GET` | `/api/v1/recordings/{id}/file` | `a.recordingFile` | [api.go:335](../../server/internal/api/api.go#L335) |
| `GET` | `/api/v1/sources` | `a.listSources` | [api.go:337](../../server/internal/api/api.go#L337) |
| `POST` | `/api/v1/sources` | `a.createSource` | [api.go:338](../../server/internal/api/api.go#L338) |
| `PUT` | `/api/v1/sources/{id}` | `a.updateSource` | [api.go:339](../../server/internal/api/api.go#L339) |
| `DELETE` | `/api/v1/sources/{id}` | `a.deleteSource` | [api.go:340](../../server/internal/api/api.go#L340) |
| `POST` | `/api/v1/sources/{id}/refresh` | `a.refreshSource` | [api.go:341](../../server/internal/api/api.go#L341) |
| `GET` | `/api/v1/sources/{id}/preview` | `a.previewSource` | [api.go:342](../../server/internal/api/api.go#L342) |
| `GET` | `/api/v1/channels` | `a.listChannels` | [api.go:344](../../server/internal/api/api.go#L344) |
| `GET` | `/api/v1/groups` | `a.listGroups` | [api.go:345](../../server/internal/api/api.go#L345) |
| `POST` | `/api/v1/channels` | `a.createChannel` | [api.go:346](../../server/internal/api/api.go#L346) |
| `PUT` | `/api/v1/channels/{id}` | `a.updateChannel` | [api.go:347](../../server/internal/api/api.go#L347) |
| `DELETE` | `/api/v1/channels/{id}` | `a.deleteChannel` | [api.go:348](../../server/internal/api/api.go#L348) |
| `POST` | `/api/v1/channels/reorder` | `a.reorderChannels` | [api.go:349](../../server/internal/api/api.go#L349) |
| `POST` | `/api/v1/channels/batch` | `a.batchChannels` | [api.go:350](../../server/internal/api/api.go#L350) |
| `GET` | `/api/v1/channels/{id}/streams` | `a.listChannelStreams` | [api.go:351](../../server/internal/api/api.go#L351) |
| `POST` | `/api/v1/channels/{id}/streams` | `a.addChannelStream` | [api.go:352](../../server/internal/api/api.go#L352) |
| `DELETE` | `/api/v1/channels/{id}/streams/{sid}` | `a.deleteChannelStream` | [api.go:353](../../server/internal/api/api.go#L353) |
| `PUT` | `/api/v1/channels/{id}/epg` | `a.mapChannelEPG` | [api.go:354](../../server/internal/api/api.go#L354) |
| `GET` | `/api/v1/epg/sources` | `a.listEPGSources` | [api.go:356](../../server/internal/api/api.go#L356) |
| `POST` | `/api/v1/epg/sources` | `a.createEPGSource` | [api.go:357](../../server/internal/api/api.go#L357) |
| `PUT` | `/api/v1/epg/sources/{id}` | `a.updateEPGSource` | [api.go:359](../../server/internal/api/api.go#L359) |
| `DELETE` | `/api/v1/epg/sources/{id}` | `a.deleteEPGSource` | [api.go:360](../../server/internal/api/api.go#L360) |
| `POST` | `/api/v1/epg/sources/{id}/refresh` | `a.refreshEPGSource` | [api.go:362](../../server/internal/api/api.go#L362) |
| `POST` | `/api/v1/epg/refresh` | `a.refreshEPG` | [api.go:363](../../server/internal/api/api.go#L363) |
| `POST` | `/api/v1/epg/import` | `a.importEPG` | [api.go:365](../../server/internal/api/api.go#L365) |
| `GET` | `/api/v1/epg/channels` | `a.listEPGChannels` | [api.go:366](../../server/internal/api/api.go#L366) |
| `GET` | `/api/v1/epg/stats` | `a.epgStats` | [api.go:370](../../server/internal/api/api.go#L370) |
| `POST` | `/api/v1/epg/cleanup` | `a.epgCleanup` | [api.go:371](../../server/internal/api/api.go#L371) |
| `GET` | `/api/v1/epg/refresh-log` | `a.epgRefreshLog` | [api.go:372](../../server/internal/api/api.go#L372) |
| `GET` | `/api/v1/epg/search` | `a.epgSearch` | [api.go:373](../../server/internal/api/api.go#L373) |
| `POST` | `/api/v1/epg/automap` | `a.epgAutomap` | [api.go:375](../../server/internal/api/api.go#L375) |
| `GET` | `/api/v1/epg/mapping` | `a.epgMapping` | [api.go:376](../../server/internal/api/api.go#L376) |
| `POST` | `/api/v1/epg/map/batch` | `a.epgMapBatch` | [api.go:377](../../server/internal/api/api.go#L377) |
| `POST` | `/api/v1/epg/map/by-number` | `a.epgMapByNumber` | [api.go:378](../../server/internal/api/api.go#L378) |
| `GET` | `/api/v1/epg/{id}` | `a.tvEPG` | [api.go:381](../../server/internal/api/api.go#L381) |
| `GET` | `/api/v1/devices` | `a.listDevices` | [api.go:383](../../server/internal/api/api.go#L383) |
| `POST` | `/api/v1/devices/bindcode` | `a.createBindCode` | [api.go:384](../../server/internal/api/api.go#L384) |
| `POST` | `/api/v1/devices/{id}/approve` | `a.approveDevice` | [api.go:385](../../server/internal/api/api.go#L385) |
| `POST` | `/api/v1/devices/{id}/reject` | `a.rejectDevice` | [api.go:386](../../server/internal/api/api.go#L386) |
| `DELETE` | `/api/v1/devices/{id}` | `a.revokeDevice` | [api.go:387](../../server/internal/api/api.go#L387) |
| `PUT` | `/api/v1/devices/{id}/config` | `a.setDeviceConfig` | [api.go:388](../../server/internal/api/api.go#L388) |
| `POST` | `/api/v1/channels/{id}/logo` | `a.uploadLogo` | [api.go:391](../../server/internal/api/api.go#L391) |
| `DELETE` | `/api/v1/channels/{id}/logo` | `a.deleteLogo` | [api.go:392](../../server/internal/api/api.go#L392) |
| `POST` | `/api/v1/auth/logout-all` | `a.revokeAdminSessions` | [api.go:396](../../server/internal/api/api.go#L396) |

### 三、终端（`requireDevice`）

共 21 条。

| 方法 | 路径 | 处理器 | 源码位置 |
|---|---|---|---|
| `GET` | `/api/v1/tv/bootstrap` | `a.tvBootstrap` | [api.go:402](../../server/internal/api/api.go#L402) |
| `GET` | `/api/v1/tv/channels` | `a.tvChannels` | [api.go:403](../../server/internal/api/api.go#L403) |
| `GET` | `/api/v1/tv/epg/{id}` | `a.tvEPG` | [api.go:404](../../server/internal/api/api.go#L404) |
| `POST` | `/api/v1/tv/favorite` | `a.tvFavorite` | [api.go:405](../../server/internal/api/api.go#L405) |
| `POST` | `/api/v1/tv/heartbeat` | `a.tvHeartbeat` | [api.go:406](../../server/internal/api/api.go#L406) |
| `GET` | `/api/v1/tv/play/{id}` | `a.tvPlay` | [api.go:407](../../server/internal/api/api.go#L407) |
| `GET` | `/api/v1/tv/streams/{id}` | `a.tvStreams` | [api.go:408](../../server/internal/api/api.go#L408) |
| `GET` | `/api/v1/tv/ws` | `a.tvWS` | [api.go:410](../../server/internal/api/api.go#L410) |
| `POST` | `/api/v1/tv/remote/{id}` | `a.tvRemote` | [api.go:411](../../server/internal/api/api.go#L411) |
| `GET` | `/api/v1/tv/remote/targets` | `a.remoteTargets` | [api.go:412](../../server/internal/api/api.go#L412) |
| `GET` | `/api/v1/tv/timeshift/{id}` | `a.tvTimeshift` | [api.go:414](../../server/internal/api/api.go#L414) |
| `POST` | `/api/v1/tv/stats` | `a.tvReportStats` | [api.go:416](../../server/internal/api/api.go#L416) |
| `POST` | `/api/v1/tv/progress` | `a.tvSaveProgress` | [api.go:418](../../server/internal/api/api.go#L418) |
| `GET` | `/api/v1/tv/resume` | `a.tvResume` | [api.go:419](../../server/internal/api/api.go#L419) |
| `POST` | `/api/v1/tv/reminder` | `a.tvCreateReminder` | [api.go:421](../../server/internal/api/api.go#L421) |
| `GET` | `/api/v1/tv/reminders` | `a.tvListReminders` | [api.go:422](../../server/internal/api/api.go#L422) |
| `DELETE` | `/api/v1/tv/reminder/{id}` | `a.tvDeleteReminder` | [api.go:423](../../server/internal/api/api.go#L423) |
| `POST` | `/api/v1/tv/record` | `a.tvCreateRecording` | [api.go:425](../../server/internal/api/api.go#L425) |
| `GET` | `/api/v1/tv/dlna/renderers` | `a.tvDLNARenderers` | [api.go:427](../../server/internal/api/api.go#L427) |
| `POST` | `/api/v1/tv/dlna/cast` | `a.tvDLNACast` | [api.go:428](../../server/internal/api/api.go#L428) |
| `POST` | `/api/v1/tv/dlna/stop` | `a.tvDLNAStop` | [api.go:429](../../server/internal/api/api.go#L429) |

## 五、时间与版本

- 服务端内部一律 **Unix 秒**：`store.Now()`（[db.go:361](../../server/internal/store/db.go#L361)）。
- JavaScript `Date` 要毫秒。前端统一走 `admin/src/utils/format.ts` 的 `toDate()`。
- 节目单的日边界由服务端按时区算好下发（`date` / `day_start` / `day_end`），客户端不得自行按设备时区计算。

## 六、怎么校验

```bash
python tools/checks/check-api-contract.py   # 服务端路由 vs 管理台调用 vs 边缘独有接口
```

该检查器只比对路径与方法，**不校验 JSON 字段、不校验请求体形状**。
字段级契约目前没有自动校验，只能靠 Go handler 与 TS/Kotlin/Swift 类型人工对齐。
