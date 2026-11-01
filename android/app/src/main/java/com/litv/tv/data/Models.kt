package com.litv.tv.data

import com.google.gson.annotations.SerializedName

/**
 * 与服务端 docs/design/01-API.md 严格对齐的数据模型。
 *
 * 注意：服务端统一用 {"code":0,"msg":"ok","data":...} 包裹，这里只描述 data 部分。
 */

/** 统一响应外壳。 */
data class ApiResp<T>(
    val code: Int = -1,
    val msg: String = "",
    val data: T? = null,
) {
    val isOk: Boolean get() = code == 0
}

data class Group(
    val name: String = "",
    val count: Int = 0,
)

data class Channel(
    val id: Long = 0,
    val number: Int = 0,
    val name: String = "",
    val group: String = "",
    val logo: String = "",
    @SerializedName("tvg_id") val tvgId: String = "",
    @SerializedName("epg_id") val epgId: String = "",
    val enabled: Boolean = true,
    val sort: Int = 0,
    @SerializedName("stream_count") val streamCount: Int = 0,
)

data class EpgProgram(
    val id: Long = 0,
    @SerializedName("epg_channel_id") val epgChannelId: Long = 0,
    @SerializedName("start_at") val startAt: Long = 0,
    @SerializedName("end_at") val endAt: Long = 0,
    val title: String = "",
    val description: String = "",
    val category: String = "",
)

data class NowPlaying(
    @SerializedName("channel_id") val channelId: Long = 0,
    val title: String = "",
    val start: Long = 0,
    val end: Long = 0,
)

data class DeviceConfig(
    /** auto | hard | soft */
    val decode: String = "auto",
    /** 缓冲毫秒 */
    @SerializedName("buffer_ms") val bufferMs: Int = 3000,
    /** auto | 16:9 | 4:3 | fill */
    val aspect: String = "auto",
    val autoplay: Boolean = true,
    /** 期望内核：auto | exo | mpv | vlc */
    val engine: String = "auto",
)

data class DeviceInfo(
    val id: Long = 0,
    val name: String = "",
    val config: DeviceConfig? = null,
)

data class ServerInfo(
    val version: String = "",
    val name: String = "LITV",
)

/** /tv/bootstrap 的返回：一次拿全，避免电视端开机打好几个请求。 */
data class Bootstrap(
    val server: ServerInfo = ServerInfo(),
    val device: DeviceInfo = DeviceInfo(),
    val groups: List<Group> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val favorites: List<Long> = emptyList(),
    val now: List<NowPlaying> = emptyList(),
)

/** /tv/play/{id} 的返回。 */
data class PlayInfo(
    val channel: PlayChannel = PlayChannel(),
    /** hls | ts */
    val kind: String = "ts",
    val url: String = "",
    val expires: Long = 0,
    @SerializedName("stream_id") val streamId: Long = 0,
)

data class PlayChannel(
    val id: Long = 0,
    val name: String = "",
    val logo: String = "",
)

/** 设备注册结果。 */
data class RegisterResult(
    @SerializedName("device_id") val deviceId: Long = 0,
    val token: String = "",
    /** pending | bound */
    val status: String = "pending",
    val hint: String = "",
)

/** 心跳返回。 */
data class HeartbeatResult(
    @SerializedName("config_version") val configVersion: Long = 0,
    @SerializedName("server_time") val serverTime: Long = 0,
)

/** 一条线路（终端只拿到标签与健康度，拿不到上游真实地址）。 */
data class TvStream(
    val id: Long = 0,
    val label: String = "",
    val quality: String = "",
    val priority: Int = 0,
    /** 1 健康 / 0 失效 / -1 未探测 */
    val health: Int = -1,
    @SerializedName("latency_ms") val latencyMs: Int = 0,
) {
    val healthText: String
        get() = when (health) {
            1 -> "正常"
            0 -> "失效"
            else -> "未检测"
        }
}
