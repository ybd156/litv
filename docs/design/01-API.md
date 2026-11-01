# LITV API 契约 v1

> 所有路径前缀：管理台 `/app/litv/api/v1/*`（网关） · 终端 `:9527/api/v1/*`（TCP）
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
