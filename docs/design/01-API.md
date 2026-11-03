# LITV API 契约 v1

> 所有路径前缀：管理台 `/app/litv/api/v1/*`（网关） · 终端 `:28027/api/v1/*`（TCP）
> 响应统一：`{"code":0,"msg":"ok","data":{...}}`，失败 `code!=0` + HTTP 4xx/5xx。

## 鉴权

| 入口 | 头 | 说明 |
|---|---|---|
| 管理台 | `X-Trim-Userid` / `X-Trim-Username` / `X-Trim-Isadmin` | 网关注入，服务端只信这个 |
| 终端 | `Authorization: Bearer <device_token>` | 绑定码换取 |
| 流 | `?sig=<hmac>&exp=<unix>` | HMAC-SHA256(channel_id|exp|device_id) |

## 公开（无鉴权）

- `GET  /api/v1/health` → `{version, uptime}`
- `POST /api/v1/device/register` `{code, platform, model, name}` → `{device_id, token}`（绑定码换 token）
- `GET  /api/v1/device/pending` → 未绑定设备列表（管理台用，需 admin）

## 管理台（需 admin）

### 源
- `GET    /api/v1/sources` 列表
- `POST   /api/v1/sources` `{name,type,url,enabled}` type: `m3u|txt|tvbox|json`
- `PUT    /api/v1/sources/{id}`
- `DELETE /api/v1/sources/{id}`
- `POST   /api/v1/sources/{id}/refresh` 立即拉取并解析
- `GET    /api/v1/sources/{id}/preview` 拉取但不入库，返回前 50 条（用于调试脏数据）

### 频道
- `GET    /api/v1/channels?group=&q=&page=&size=` → `{total, items:[{id,number,name,group,logo,tvg_id,epg_id,enabled,stream_count}]}`
- `POST   /api/v1/channels` / `PUT /{id}` / `DELETE /{id}`
- `POST   /api/v1/channels/reorder` `{ids:[...]}` 批量排序
- `POST   /api/v1/channels/batch` `{ids:[...], action:"enable|disable|delete|set_group", value}`
- `GET    /api/v1/channels/{id}/streams` 该频道所有线路
- `POST   /api/v1/channels/{id}/streams` 加线路
- `DELETE /api/v1/channels/{id}/streams/{sid}`
- `GET    /api/v1/groups` → 分组名 + 计数

### EPG
- `GET    /api/v1/epg/sources` / `POST` / `DELETE /{id}`
- `POST   /api/v1/epg/refresh`
- `GET    /api/v1/epg/{channel_id}?day=0` → `[{start,end,title,desc}]`
- `GET    /api/v1/epg/now?channel_ids=1,2,3` → 批量当前节目（TV 首页用）

### 设备
- `GET    /api/v1/devices` 列表（含在线状态、最后心跳）
- `POST   /api/v1/devices/bindcode` 生成 6 位绑定码（TTL 5 分钟）
- `POST   /api/v1/devices/{id}/approve` / `POST /{id}/reject`
- `DELETE /api/v1/devices/{id}` 吊销
- `PUT    /api/v1/devices/{id}/config` 远程配置覆盖

### 系统
- `GET    /api/v1/system/info` 版本/架构/运行时长/并发流数
- `GET    /api/v1/system/logs?lines=200`
- `GET    /api/v1/system/config` / `PUT`

## 终端（需 device token）

- `GET  /api/v1/tv/bootstrap` → 一次性返回：分组、频道列表、我的收藏、服务端配置、EPG 快照
- `GET  /api/v1/tv/channels?group=` 频道列表
- `GET  /api/v1/tv/epg/{channel_id}?day=0`
- `POST /api/v1/tv/favorite` `{channel_id, on}`
- `POST /api/v1/tv/heartbeat` `{playing_channel, state}` → `{config_version}`（有变化就重新拉配置）
- `GET  /api/v1/tv/play/{channel_id}` → `{url, kind:"hls|ts", headers:{}}` 带签名 URL

## 流

- `GET /live/{channel_id}.m3u8?sig=&exp=` HLS 清单（服务端切片）
- `GET /live/{channel_id}.ts?sig=&exp=` 裸 TS 透传（低延迟）
- `GET /live/{channel_id}/seg/{n}.ts` HLS 分片

## WebSocket

- `/app/litv/ws` 管理台：设备上下线、转码进度、日志尾巴
- 消息格式 `{type, data}`

## 错误码

| code | 含义 |
|---|---|
| 0 | ok |
| 1001 | 参数错误 |
| 2001 | 未认证 |
| 2002 | 无权限 |
| 2003 | 设备未绑定 / token 失效 |
| 3001 | 频道不存在 |
| 3002 | 无可用线路 |
| 4001 | 上游拉流失败 |
| 5001 | 内部错误 |

---

# 契约定稿（v1.1，前后端已对齐并实测）

> 本节是**最终形状**，前面的列表是概览。前端按这里实现，服务端按这里返回。

## 1. `GET /api/v1/system/me`
```json
{"uid":"1000","user_id":"1000","username":"admin","is_admin":true,"via_gateway":true}
```
未认证返回 HTTP 401 + `code:2001`，前端据此显示「请从飞牛桌面打开本应用」。
`uid` 与 `user_id` 同义（保留双字段仅为兼容）。

## 2. `GET /api/v1/system/info`
概览页一次拿全，不用打多个接口：
```json
{
  "version":"1.0.0","uptime_s":3600,"active_channels":3,"max_concurrent":100,
  "port":28027,"prefix":"/app/litv","gateway_socket":"...","transcode":false,"db_path":"...",
  "channels_total":51,"channels_enabled":48,
  "devices_total":5,"devices_online":2,"devices_pending":1,
  "sources_total":4,"sources_failed":1,
  "plays_today":37
}
```
`devices_online` 判定：`status=bound` 且最近 **90 秒**内有心跳。
`plays_today` 来自 `audit_logs` 中 `action='play'` 的当日记录（每次 `tv/play` 记一条）。

## 3. `GET /api/v1/system/config` / `PUT`
```json
{
  "port":28027,"prefix":"/app/litv","max_concurrent":100,"ring_size":2097152,
  "transcode":false,"transcode_enabled":false,
  "hwaccel":"qsv","epg_enabled":true,"epg_refresh_hour":3,
  "token_hours":8760,"log_level":"info","allow_lan":true
}
```
`transcode` 与 `transcode_enabled` 同义，PUT 时**两个都接受**。
运行期可改：`max_concurrent`、`transcode_enabled`；`port` 需改 config.yaml 后重启。

## 4. `GET /api/v1/system/logs`
```json
{"hint":"日志文件位于 $TRIM_PKGVAR/litv.log","lines":[]}
```
固定返回 `lines: string[]`（M1 暂为空数组，后续接环形缓冲）。

## 5. `GET /api/v1/devices`
```json
[{
  "id":1,"name":"客厅电视","platform":"androidtv","model":"MiBox",
  "status":"bound","online":true,
  "last_heartbeat_at":1793538814,"last_seen":1793538814,
  "bound_at":1793538814,"created_at":1793538813,
  "ip":"","app_version":"","config":{}
}]
```
`status`: `pending` | `bound` | `revoked`。**不返回 `device_key`**（客户端稳定标识，属敏感信息）。

## 6. `GET /api/v1/epg/channels`
```json
[{"id":1,"xmltv_id":"cctv1","name":"CCTV-1","display_name":"CCTV-1 综合",
  "icon":"","source":"51zmt","source_id":1,"program_count":42}]
```
`name` 已做兜底（`display_name` 为空时回落到 `xmltv_id`）。

## 7. `POST /api/v1/channels/reorder` `{ids:[...]}`
**语义：相对重排**。这些 id 占用它们原有的 `sort` 槽位集合，按传入顺序重新填回，
**其余频道不动**。所以前端在「分页 + 分组筛选」下只提交当前页 id 是正确用法。

## 8. `GET /api/v1/sources/{id}/preview`
```json
{"total":50,"groups":{"央视频道":20,"卫视频道":30},"items":[{"Name":"...","Group":"...","URL":"...","Logo":"","TvgID":"","Number":0}]}
```
`items` 是只读预览，**不入库**。`groups` 是分组计数，方便一眼看出脏数据。

## 9. `POST /api/v1/devices/bindcode` `{device_id?}`
```json
{"code":"838679","expires_in":300}
```
6 位数字，TTL 300 秒，一次性消费。

## 10. `PUT /api/v1/devices/{id}/config`
```json
{"decode":"hard|soft|auto","buffer_ms":3000,"aspect":"auto|16:9|4:3|fill","autoplay":true}
```
服务端按 JSON 原样存储并随心跳下发，不做字段校验（便于后续加参数不发版）。

## 11. `GET /api/v1/channels?group=<分组名>`
`group` 传**分组名称**（与 `/groups` 返回的 `name` 一致），不是 id。

## 12. `GET /api/v1/device/pending` —— 已废弃
改用 `GET /api/v1/devices?status=pending`（需 admin）。原文档把它放在「公开」段是笔误。

