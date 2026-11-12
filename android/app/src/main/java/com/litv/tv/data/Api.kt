package com.litv.tv.data

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LITV 终端 API 客户端。
 *
 * 设计取舍：
 *  - 不用 Retrofit：接口就十来个，Retrofit + 注解处理器会让构建变重、TV 上收益为零。
 *  - 不用协程：回调式足够，且能在低端盒子上少一层调度。
 *  - 单例 OkHttp：连接复用对"换台要快"很关键（省掉每次 TLS/TCP 握手）。
 */
class Api(private val prefs: Prefs) {

    private val gson = Gson()
    private val main = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 播放用的客户端：直播是长连接，不能有 readTimeout。 */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // ---------------------------------------------------------------- 基础设施

    fun <T> call(
        path: String,
        method: String = "GET",
        body: Any? = null,
        auth: Boolean = true,
        clazz: Class<T>,
        onOk: (T?) -> Unit,
        onErr: (String) -> Unit,
    ) {
        val base = prefs.serverBase
        if (base.isBlank()) {
            onErr("还没设置服务器地址")
            return
        }
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val builder = Request.Builder().url(url)
        if (auth && prefs.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${prefs.token}")
        }
        when (method) {
            "GET" -> builder.get()
            "POST", "PUT", "DELETE" -> {
                val json = if (body == null) "{}" else gson.toJson(body)
                builder.method(method, json.toRequestBody(JSON))
            }
        }
        builder.header("Accept", "application/json")

        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                main.post { onErr("网络错误：${e.message ?: "连接失败"}") }
            }

            override fun onResponse(c: Call, resp: Response) {
                resp.use {
                    val text = try {
                        it.body?.string().orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    if (text.isBlank()) {
                        main.post { onErr("服务器返回空响应（HTTP ${it.code}）") }
                        return
                    }
                    try {
                        val root = JsonParser.parseString(text).asJsonObject
                        val code = root.get("code")?.asInt ?: -1
                        val msg = root.get("msg")?.asString ?: ""
                        if (code != 0) {
                            main.post { onErr(if (msg.isNotBlank()) msg else "错误码 $code") }
                            return
                        }
                        val dataEl = root.get("data")
                        val value = if (dataEl == null || dataEl.isJsonNull) null
                        else gson.fromJson(dataEl, clazz)
                        main.post { onOk(value) }
                    } catch (e: Exception) {
                        main.post { onErr("解析响应失败：${e.message}") }
                    }
                }
            }
        })
    }

    /** 列表版本：Gson 泛型擦除，单独开一个入口避免踩坑。 */
    fun <T> callList(
        path: String,
        clazz: Class<T>,
        onOk: (List<T>) -> Unit,
        onErr: (String) -> Unit,
    ) {
        val base = prefs.serverBase
        if (base.isBlank()) {
            onErr("还没设置服务器地址")
            return
        }
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val req = Request.Builder().url(url)
            .apply { if (prefs.token.isNotBlank()) header("Authorization", "Bearer ${prefs.token}") }
            .get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                main.post { onErr("网络错误：${e.message ?: "连接失败"}") }
            }

            override fun onResponse(c: Call, resp: Response) {
                resp.use {
                    val text = try {
                        it.body?.string().orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    try {
                        val root = JsonParser.parseString(text).asJsonObject
                        val code = root.get("code")?.asInt ?: -1
                        if (code != 0) {
                            main.post { onErr(root.get("msg")?.asString ?: "错误码 $code") }
                            return
                        }
                        val arr = root.getAsJsonArray("data")
                        val out = arr.map { el -> gson.fromJson(el, clazz) }
                        main.post { onOk(out) }
                    } catch (e: Exception) {
                        main.post { onErr("解析响应失败：${e.message}") }
                    }
                }
            }
        })
    }

    // ---------------------------------------------------------------- 业务接口

    /** 探测一个地址是不是 LITV 服务端（绑定页用）。 */
    fun probe(base: String, onResult: (Boolean, String) -> Unit) {
        val url = base.trimEnd('/') + "/api/v1/health"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                main.post { onResult(false, "连不上：${e.message ?: ""}") }
            }

            override fun onResponse(c: Call, resp: Response) {
                resp.use {
                    val ok = it.isSuccessful && (it.body?.string()?.contains("\"code\":0") == true)
                    main.post { onResult(ok, if (ok) "" else "HTTP ${it.code}") }
                }
            }
        })
    }

    fun register(bindCode: String?, onOk: (RegisterResult) -> Unit, onErr: (String) -> Unit) {
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val body = mapOf(
            "device_key" to prefs.deviceKey,
            "name" to prefs.deviceName.ifBlank { model },
            "platform" to "androidtv",
            "model" to model,
            // 版本号只用于管理台展示"这台设备装的是哪版"
            "app_version" to com.litv.tv.BuildConfig.VERSION_NAME,
            "code" to (bindCode ?: ""),
        )
        call("/api/v1/device/register", "POST", body, auth = false,
            clazz = RegisterResult::class.java,
            onOk = { r ->
                if (r != null) {
                    prefs.token = r.token
                    prefs.deviceId = r.deviceId
                }
                onOk(r ?: RegisterResult())
            },
            onErr = onErr)
    }

    fun bootstrap(onOk: (Bootstrap) -> Unit, onErr: (String) -> Unit) {
        call("/api/v1/tv/bootstrap", clazz = Bootstrap::class.java,
            onOk = { if (it != null) onOk(it) else onErr("空数据") }, onErr = onErr)
    }

    /**
     * 把服务端返回的播放地址变成播放器能用的绝对地址。
     *
     * 服务端给的是站内相对路径（/app/litv/live/1/stream.ts），
     * 直接丢给 ExoPlayer/VLC 会因为没有 scheme 而失败。
     * 直连模式下给的是上游绝对地址，原样返回。
     *
     * 拼接待实现在 [UrlJoin] 里：那是纯函数，可以脱离 Android 框架单测
     * （这段逻辑拼错的表现是"全黑屏 + 日志里只有一串 URI"，很难在真机上反推）。
     */
    fun absolute(url: String): String = UrlJoin.absolute(prefs.serverBase, url)

    /**
     * 播放地址缓存：聚焦时预取，按 OK 直接用。
     *
     * 换台的感知延迟主要是「一次网络往返 + 引擎 prepare」。
     * 焦点在列表上移动时就把地址取回来，按 OK 时只剩 prepare，快一截。
     * 服务端签名有效期 12 小时，这里只缓存 5 分钟，够用且不会用到过期地址。
     */
    private val playCache = HashMap<Long, Pair<PlayInfo, Long>>()

    /** 预取播放地址（失败静默，按 OK 时还会再取一次） */
    fun prefetchPlay(channelId: Long, direct: Boolean) {
        val hit = playCache[channelId]
        if (hit != null && System.currentTimeMillis() - hit.second < 5 * 60_000) return
        val params = buildList {
            if (direct) add("direct=1")
        }
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        call("/api/v1/tv/play/$channelId$q", clazz = PlayInfo::class.java,
            onOk = { info ->
                if (info != null) playCache[channelId] = info to System.currentTimeMillis()
            },
            onErr = { /* 预取失败不影响正常流程 */ })
    }

    /** 取播放地址。
     *
     * direct=true 时服务端会把**上游原始地址**直接给回来（mode=direct），
     * 客户端自己连源站——省掉 NAS 的带宽和 CPU，起播也更快。
     * 服务端不允许直连、或这条线路走转封装时，会自动回落到中转。
     */
    fun play(
        channelId: Long,
        streamId: Long = 0,
        direct: Boolean = false,
        onOk: (PlayInfo) -> Unit,
        onErr: (String) -> Unit,
    ) {
        // 有预取的地址就直接用，省一次网络往返
        if (streamId == 0L) {
            val hit = playCache[channelId]
            if (hit != null && System.currentTimeMillis() - hit.second < 5 * 60_000) {
                onOk(hit.first)
                return
            }
        }
        val params = buildList {
            if (streamId > 0) add("stream_id=$streamId")
            if (direct) add("direct=1")
        }
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        call("/api/v1/tv/play/$channelId$q", clazz = PlayInfo::class.java,
            onOk = { info ->
                if (info != null) {
                    // 顺手写缓存：下次切回这个台不用再请求
                    if (streamId == 0L) playCache[channelId] = info to System.currentTimeMillis()
                    onOk(info)
                } else onErr("空数据")
            }, onErr = onErr)
    }

    /** 线路列表（手动切线路用）。 */
    fun streams(channelId: Long, onOk: (List<TvStream>) -> Unit, onErr: (String) -> Unit) {
        callList("/api/v1/tv/streams/$channelId", TvStream::class.java, onOk, onErr)
    }

    fun epg(channelId: Long, day: Int, onOk: (List<EpgProgram>) -> Unit, onErr: (String) -> Unit) {
        // 服务端 data 是对象 {channel, programs}，不是数组。
        // 用 callList 按数组解析会永远失败（节目单空白）。
        call(
            "/api/v1/tv/epg/$channelId?day=$day",
            clazz = EpgResponse::class.java,
            onOk = { r -> onOk(r?.programs ?: emptyList()) },
            onErr = onErr,
        )
    }

    /** 列出局域网里可投屏的 DLNA 设备（发现由服务端做，iOS 拿不到多播权限）。 */
    fun dlnaRenderers(onOk: (List<DlnaRenderer>) -> Unit, onErr: (String) -> Unit) {
        call("/api/v1/tv/dlna/renderers", clazz = DlnaRenderersResponse::class.java,
            onOk = { onOk(it?.items ?: emptyList()) }, onErr = onErr)
    }

    /** 让指定设备播放某个频道。 */
    fun dlnaCast(
        channelId: Long,
        controlUrl: String,
        name: String,
        onOk: () -> Unit = {},
        onErr: (String) -> Unit = {},
    ) {
        call("/api/v1/tv/dlna/cast", "POST",
            mapOf("channel_id" to channelId, "control_url" to controlUrl, "name" to name),
            clazz = Any::class.java,
            onOk = { onOk() }, onErr = onErr)
    }

    /** 让指定设备停止投屏。 */
    fun dlnaStop(controlUrl: String, onOk: () -> Unit = {}, onErr: (String) -> Unit = {}) {
        call("/api/v1/tv/dlna/stop", "POST", mapOf("control_url" to controlUrl),
            clazz = Any::class.java, onOk = { onOk() }, onErr = onErr)
    }

    /** 时移回看：问服务端这个台能回看多久，拿带签名的地址。 */
    fun timeshift(
        channelId: Long,
        onOk: (TimeshiftInfo) -> Unit,
        onErr: (String) -> Unit,
    ) {
        call(
            "/api/v1/tv/timeshift/$channelId",
            clazz = TimeshiftInfo::class.java,
            onOk = { if (it != null) onOk(it) },
            onErr = onErr,
        )
    }

    /** C5：设置节目开播提醒。 */
    fun createReminder(
        channelId: Long,
        title: String,
        startAt: Long,
        onOk: () -> Unit = {},
        onErr: (String) -> Unit = {},
    ) {
        call(
            "/api/v1/tv/reminder", "POST",
            mapOf("channel_id" to channelId, "title" to title, "start_at" to startAt),
            clazz = Any::class.java,
            onOk = { onOk() }, onErr = onErr,
        )
    }

    /** C6：从节目单预约录制。 */
    fun createRecording(
        channelId: Long,
        name: String,
        startAt: Long,
        durationSec: Int,
        onOk: () -> Unit = {},
        onErr: (String) -> Unit = {},
    ) {
        call(
            "/api/v1/tv/record", "POST",
            mapOf(
                "channel_id" to channelId,
                "name" to name,
                "start_at" to startAt,
                "duration_sec" to durationSec,
            ),
            clazz = Any::class.java,
            onOk = { onOk() }, onErr = onErr,
        )
    }

    fun favorite(channelId: Long, on: Boolean, onOk: () -> Unit = {}, onErr: (String) -> Unit = {}) {
        call("/api/v1/tv/favorite", "POST",
            mapOf("channel_id" to channelId, "on" to on),
            clazz = Any::class.java,
            onOk = { onOk() }, onErr = onErr)
    }

    fun heartbeat(playingChannel: Long, state: String, onOk: (HeartbeatResult) -> Unit = {}) {
        call("/api/v1/tv/heartbeat", "POST",
            mapOf(
                "playing_channel" to playingChannel,
                "state" to state,
                "app_version" to com.litv.tv.BuildConfig.VERSION_NAME,
            ),
            clazz = HeartbeatResult::class.java,
            onOk = { if (it != null) onOk(it) }, onErr = { /* 心跳失败不打扰用户 */ })
    }

    /**
     * 播放质量上报。
     *
     * 为什么由客户端报：服务端只知道"字节发出去了"，不知道
     * "用户看到第一帧用了多久"、"播放器卡没卡"——这些只有客户端能测准。
     *
     * 新增的 [stallMs]/[watchMs]/[path]/[engine] 只在 **event == "stall"** 时才发送：
     * 服务端 `decode()` 开了 `DisallowUnknownFields`，老版本服务端收到不认识的
     * 字段会返回 400。把它们限制在 stall 事件里，`start`/`error` 的报文与旧版本
     * 完全一致，所以「新 APK + 旧 NAS」这种升级顺序下不会多出任何失败请求。
     * 代价：旧服务端下 stall 事件会被拒（静默丢弃），只影响统计数据，不影响播放。
     *
     * @param stallMs 本次卡死的持续时长（毫秒）
     * @param watchMs 本台累计观看时长（秒），rebuffer ratio 的分母
     * @param path    播放链路：ts | hls | remux（取 PlayInfo.kind，**不是** direct/relay）
     * @param engine  内核 key：exo | vlc | mpv | avs3
     */
    fun reportStats(
        channelId: Long,
        event: String,
        startupMs: Int = 0,
        detail: String = "",
        stallMs: Int = 0,
        watchMs: Int = 0,
        path: String = "",
        engine: String = "",
    ) {
        val body = StatsPayload.build(
            channelId = channelId, event = event, startupMs = startupMs, detail = detail,
            stallMs = stallMs, watchMs = watchMs, path = path, engine = engine,
        )
        call("/api/v1/tv/stats", "POST", body, clazz = Any::class.java,
            onOk = { }, onErr = { /* 上报失败静默：不能因为埋点打断播放 */ })
    }

    /** 跨端续播：上报"我在看哪个台、看到第几秒"。 */
    fun saveProgress(channelId: Long, positionSec: Int = 0) {
        call("/api/v1/tv/progress", "POST",
            mapOf("channel_id" to channelId, "position_sec" to positionSec),
            clazz = Any::class.java, onOk = { }, onErr = { })
    }

    /** 跨端续播：查最近在别的设备上看过的频道。 */
    fun resume(onOk: (ResumePoint?) -> Unit) {
        call("/api/v1/tv/resume", clazz = ResumePoint::class.java,
            onOk = { onOk(it) }, onErr = { onOk(null) })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
