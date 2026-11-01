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
    /**
     * 这条线路专属的 User-Agent（来自源的配置）。
     * 客户端直连上游时要用它，否则源站会 403。空 = 用默认 UA。
     */
    val ua: String = "",
    /** direct = 客户端直连源站；relay = 走服务端中转 */
    val mode: String = "relay",
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

/** 跨端续播：最近在别的设备上看过的频道。 */
data class ResumePoint(
    val available: Boolean = false,
    @SerializedName("channel_id") val channelId: Long = 0,
    @SerializedName("channel_name") val channelName: String = "",
    @SerializedName("channel_logo") val channelLogo: String = "",
    val group: String = "",
    @SerializedName("position_sec") val positionSec: Int = 0,
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("age_seconds") val ageSeconds: Int = 0,
    @SerializedName("timeshift_available") val timeshiftAvailable: Int = 0,
) {
    /** 人类可读的"多久之前"。 */
    val ageText: String
        get() = when {
            ageSeconds < 60 -> "刚刚"
            ageSeconds < 3600 -> "${ageSeconds / 60} 分钟前"
            ageSeconds < 86400 -> "${ageSeconds / 3600} 小时前"
            else -> "${ageSeconds / 86400} 天前"
        }
}

/** 一条线路（终端只拿到标签与健康度，拿不到上游真实地址）。 */data class TvStream(
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
