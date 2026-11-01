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
     * 取播放地址。
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
        val params = buildList {
            if (streamId > 0) add("stream_id=$streamId")
            if (direct) add("direct=1")
        }
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        call("/api/v1/tv/play/$channelId$q", clazz = PlayInfo::class.java,
            onOk = { if (it != null) onOk(it) else onErr("空数据") }, onErr = onErr)
    }

    /** 线路列表（手动切线路用）。 */
    fun streams(channelId: Long, onOk: (List<TvStream>) -> Unit, onErr: (String) -> Unit) {
        callList("/api/v1/tv/streams/$channelId", TvStream::class.java, onOk, onErr)
    }

    fun epg(channelId: Long, day: Int, onOk: (List<EpgProgram>) -> Unit, onErr: (String) -> Unit) {
        callList("/api/v1/tv/epg/$channelId?day=$day", EpgProgram::class.java, onOk, onErr)
    }

    fun favorite(channelId: Long, on: Boolean, onOk: () -> Unit = {}, onErr: (String) -> Unit = {}) {
        call("/api/v1/tv/favorite", "POST",
            mapOf("channel_id" to channelId, "on" to on),
            clazz = Any::class.java,
            onOk = { onOk() }, onErr = onErr)
    }

    fun heartbeat(playingChannel: Long, state: String, onOk: (HeartbeatResult) -> Unit = {}) {
        call("/api/v1/tv/heartbeat", "POST",
            mapOf("playing_channel" to playingChannel, "state" to state),
            clazz = HeartbeatResult::class.java,
            onOk = { if (it != null) onOk(it) }, onErr = { /* 心跳失败不打扰用户 */ })
    }

    /**
     * 播放质量上报。
     *
     * 为什么由客户端报：服务端只知道"字节发出去了"，不知道
     * "用户看到第一帧用了多久"、"播放器卡没卡"——这些只有客户端能测准。
     */
    fun reportStats(channelId: Long, event: String, startupMs: Int = 0, detail: String = "") {
        call("/api/v1/tv/stats", "POST",
            mapOf(
                "channel_id" to channelId,
                "event" to event,
                "startup_ms" to startupMs,
                "detail" to detail,
            ),
            clazz = Any::class.java,
            onOk = { }, onErr = { /* 上报失败静默 */ })
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
