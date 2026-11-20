# 内置源（央视频）实现说明

> 2026-11-21 定稿。写给以后接手的人：为什么这么绕、每条链路的边界在哪。
> 代码：`android/app/src/main/java/com/litv/tv/data/{YspCrypto,YspClient,LocalHlsProxy}.kt`

---

## 1. 为什么不能直接把 URL 交给播放器

央视频的 m3u8 地址是**按 URL 签名**的，且 CDN **认发起请求的 TLS 栈**。

实测同一个 URL、同一时刻、同一台手机，三种客户端结果完全不同：

| 客户端 | 直播 m3u8 | 直播分片 | 回看 m3u8 | 回看分片 |
|---|---|---|---|---|
| `HttpURLConnection`（Android Conscrypt） | ❌ 403 | ❌ 403 | ❌ Connection reset | ❌ Connection reset |
| ExoPlayer 自带数据源 / VLC 的 ffmpeg | ❌ 403 | ❌ 403 | — | — |
| Cronet（Chromium BoringSSL） | ✅ | ❌ `ERR_CONNECTION_RESET` | ✅ | ❌ `ERR_CONNECTION_RESET` |
| **OkHttp（自带 TLS + 强制 HTTP/1.1）** | — | — | ✅ | ✅ |
| App 自己用 `HttpURLConnection` 直连 | ✅ | ✅ | — | — |

**结论**：播放器发出的请求一律过不了。但 **App 进程自己发出的 `HttpURLConnection` 请求能过**（直播），回看那条 CDN 更严、得用 **OkHttp**。

所以整个方案的核心是：**把取流收回到 App 自己手里，播放器只连 `127.0.0.1`。**

---

## 2. 三层结构

```
┌─────────────────────────────────────────────────────┐
│ UI 层  PlayerActivity / PhoneHomeActivity            │
│   · 频道表注入（内置源 id 为负数，避开服务端源 1 起） │
│   · 起播时向代理要地址，不直接连源站                  │
├─────────────────────────────────────────────────────┤
│ 协议层  YspClient                                    │
│   · cKey 票据（YspCrypto 算）                        │
│   · bkliveinfo 取 playurl / backurl_list              │
│   · 直播 lines() / 回看 replayLines()                 │
├─────────────────────────────────────────────────────┤
│ 代理层  LocalHlsProxy（127.0.0.1 随机端口）           │
│   · /ch/{id}.m3u8    → 直播 playlist，改写分片地址     │
│   · /rp/{id}/{s}/{e}.m3u8 → 回看 playlist + 窗口游标   │
│   · /seg/{base64}    → 分片代拉                       │
└─────────────────────────────────────────────────────┘
```

播放器拿到的永远是 `http://127.0.0.1:{port}/ch/-1001.m3u8` 这种本地地址。

---

## 3. cKey：为什么必须本地算

`bkliveinfo.ysp.cctv.cn` 要求一个 `cKey` 票据，它是**加密过的二进制**，不是服务端下发的。

`YspCrypto` 做的事：

1. 拼明文包（platform、appVer、guid、时间戳、guard 串…）
2. **TEA** 加密（16 轮，delta `0x9e3779b9`，大端），用 CBC 式链式 XOR 做块间混合
3. 算 `cksum`（多项式 `0x83`，`& 0x7fffffff`）**回填到包体第 18..22 字节**
4. 再整体 XOR 混淆，最后 **base64**

**这两个坑都踩过，记一下**：

- **cksum 必须回填**。曾经因为一行被注释吃掉，`pkt[18..22]` 恒为 0，服务端一律 `iretcode=32`
- **base64 是变体**：`_`=62、`-`=63，和标准 base64url（`-`=62、`_`=63）**正好相反**。这一处差异导致 `iretcode=32` 查了很久

---

## 4. 直播链路

```
1. PlayerActivity 起播 → LocalHlsProxy.urlFor(ch.id) → http://127.0.0.1:P/ch/-1001.m3u8
2. 播放器请求该地址 → servePlaylist()
3. lineCache 有活着的 playurl？ → 直接用
   否则 YspClient.firstLine() 现取（cKey + bkliveinfo，约 400~900ms）
4. 拉 playlist，把分片地址改写成 /seg/{base64}
5. 播放器请求 /seg/xxx → serveSegment() 代拉真实分片并转发
```

**缓存两级，各有理由**：

| 缓存 | TTL | 为什么是这个值 |
|---|---|---|
| `lineCache`（playurl） | **25 秒** | 央视 playurl 约 30~40 秒失效；且 `bkliveinfo` **调太勤会被限流**（曾每 2 秒调一次导致满屏 502） |
| `cache`（playlist） | **4 秒** | 直播 m3u8 的 `MEDIA-SEQUENCE` 每 ~5 秒才推进。设 15 秒会让播放器两次拿到同一份 → 判「没有新分片」→ 停止拉流 → 判卡死 |

**失败处理（这三条是卡顿修复的核心）**：

- 拉 playlist 失败 → **立刻清 `lineCache`**（否则 3 次重试全打在同一个死链上，每次撞满超时）
- 失败但有缓存 → **用上一份 playlist 顶**（清单只是「有哪些分片」，旧的也能播）
  - ⚠️ **这条路径同样必须清 `lineCache`**，否则线路永远换不掉，兜底会把系统锁死在旧数据上
- 分片 403 → **清空全部 playlist 缓存**（分片地址过期了，下次重取）

超时：m3u8 **3 秒**（才 2KB，3 秒拿不到就是这条线不行了）、分片 **12 秒**。

---

## 5. 回看链路

**协议**和直播是**同一个接口**，只多三个参数：

```
cmd=2（不是 1，cmd=1 返回 iretcode=21 无效命令字）
starttime=<unix 秒>
endtime=<unix 秒>
playbacktime=<starttime>
```

返回里 `playback.playbacktime = 604800`（7 天），m3u8 落在 `tlivecloud-playback-cdn.ysp.cctv.cn`。

**两个必须知道的坑**：

1. **返回的 URL 含 `\u0026`**（`&` 的转义）。用正则从原始文本抠会把 `&svrtime=…` 当成路径的一部分 → CDN 403。**必须走 `JSONObject` 解析**（它会自动解码）
2. **一次只回 5 个分片（约 25 秒）**，`endtime` 给多大都没用。所以 `/rp/` 路由维护**窗口游标**：播放器每次重拉 playlist 时，把 `starttime` 往前推一个窗口

**因此回看没有进度条** —— 它是 25 秒窗口的时移流，不是点播文件，ExoPlayer 当直播处理，没有 duration、不能拖。要真进度条得逆央视网的**点播**接口（`vdn.apps.cntv.cn` 那套，要 `videoId` 而不是 `sid`）。

---

## 6. 台标与节目单

**台标**：图源 `github.com/fanmingming/live` 的 `tv/` 目录（933 张，含 23 张 CCTV + 8 张 CGTN）。

- 该仓库自带的 `live.fanmingming.com` 解析到 Cloudflare 任播 `104.244.43.6`，**国内 TLS 握手超时** → 改用 `cdn.jsdelivr.net/gh/fanmingming/live@main/tv/`
- `@main` 意味着**跟随上游最新内容**，上游换台标客户端自动生效，不用发版

**节目单**：复用设备上的 xmltv（`files/epg.xml`，124 频道 / 123 唯一名），按名字归一化匹配：

```
「CCTV-1 综合」→ 去 -/空格 → cctv1综合 → 前缀匹配 → CCTV1
```

**必须懒查**：`withBuiltin()` 往往在 xmltv 加载完之前就跑了，那时 `idByName` 返回空，算出来的空值会被 `copy` 进 `Channel` 永久缓存 → 节目单永远空。所以放在 `epgIdOf(ch)` 里，**用到时才查**。

---

## 7. 频道表远程更新

`config/ysp-channels.json`（schema:1，字段 `slug`/`name`/`sid`/`pid`/`defn`）。

客户端三级取值：

```
编译进包的 HARDCODED_CHANNELS  →  filesDir 缓存  →  jsDelivr @main
```

央视频的 `cnlid`/`livepid` 会变（换号、临时下架、新增频道），改这个 JSON 推到 main 就行，**不用发版**。拉不到就用上一份缓存，再不行用内置表，功能不受影响。

---

## 8. 已知限制

| 项 | 说明 |
|---|---|
| 回看无进度条 | 协议是时移流，原因见 §5 |
| 回看游标连续性 | 连续播 1 分钟以上是否平滑推进，未充分验证 |
| `iretcode=12` | CCTV-10 等台央视自己不给直播信号（「应版权方要求」），应按「无授权」提示而不是报「播放失败」 |
| `CGTN`/`CGTN 俄语`/`阿语`/`纪录` | xmltv 里没有对应 display-name，节目单为空 |
| `CCTV-8K 超高清` | 前缀匹配撞上 `cctv8`，错配成 CCTV-8 |
| 换台偏慢 | 内置源比自建源多一次 `bkliveinfo`（400~900ms）。可做「面板焦点预取」缓解 |
| 电视端 armeabi-v7a | `LocalHlsProxy` 只在 arm64 手机与 x86 模拟器验过 |
| iOS | 完全没做，包括这套代理方案 |

---

## 9. 如果哪天又坏了，按这个顺序查

1. **`iretcode` 是多少**：32 = cKey 算法/常量过期；12 = 该台无授权；0 = 正常
2. **`iretcode=32`** → 先查 `YspCrypto` 的 base64 基表（`_-` 还是 `-_`）、`cksum` 是否回填
3. **取流正常但播放器 403** → 检查是不是给 URL 加了参数（签名 URL 一个多余参数就 403，`play(bustCache=false)`）
4. **只有某几条代理链路挂** → 看该 CDN 用哪个 TLS 栈能过：先试 OkHttp，再考虑别的
5. **一卡一卡** → 看日志里「加载慢 xxxms / 2KB」：2KB 就是 m3u8，说明取流慢/失败，不是分片问题
