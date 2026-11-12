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
    /** 所属源，电视端按源分类用 */
    @SerializedName("source_id") val sourceId: Long = 0,
)

/** /tv/timeshift/{id} 的返回：这个台能回看多久、地址是什么。 */
data class TimeshiftInfo(
    val enabled: Boolean = false,
    @SerializedName("available_seconds") val availableSeconds: Int = 0,
    val url: String = "",
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

/**
 * /tv/epg/{id} 的返回外壳。
 *
 * 服务端 data 是**对象**不是数组（见 handlers_client.go 的 tvEPG）：
 *   {"channel":{…},"programs":[…]}                          正常
 *   {"channel":{…},"programs":[],"note":"该频道未关联 EPG"}  未关联
 *
 * 以前这里用 callList 按数组解析，而服务端给的是对象，
 * 于是永远解析失败 —— 表现就是「节目单一直无法获取」。
 * iOS 侧已修过同类问题（R07），安卓漏了。
 */
/** 局域网里一个可投屏的 DLNA 设备。 */
data class DlnaRenderer(
    val udn: String = "",
    val name: String = "",
    val model: String = "",
    val location: String = "",
    @SerializedName("control_url") val controlUrl: String = "",
)

/** /tv/dlna/renderers 的返回：{total, items} */
data class DlnaRenderersResponse(
    val total: Int = 0,
    val items: List<DlnaRenderer> = emptyList(),
)

data class EpgResponse(
    val channel: Channel? = null,
    val programs: List<EpgProgram> = emptyList(),
    val note: String = "",
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
    val sources: List<SourceBrief> = emptyList(),
    /**
     * xmltv 节目单地址。服务端只下发这个，**不存节目数据**（D1 按行计费，
     * 一份全量节目单几千行）。客户端拿它自己下载解析、本地缓存，见 [XmltvEpg]。
     * 为空表示源里没有节目单地址。
     */
    @SerializedName("epg_url") val epgUrl: String = "",
    /** 与心跳同源的频道指纹，拿到 bootstrap 时记下基线。 */
    @SerializedName("channels_version") val channelsVersion: String = "",
)

/** /tv/play/{id} 的返回。 */
/**
 * 上次成功起播的地址缓存。
 *
 * 为什么要它：启动时要先请求 /tv/play 拿到带签名的地址，这一步是**串行**的
 * 网络往返（实测占启动耗时近 3 秒）。而签名地址本身有有效期，
 * 只要还没过期就可以直接拿来起播，网络请求放到后台去做。
 */
data class PlayCache(
    /**
     * 缓存身份：服务端 origin + 会话代际 + 线路 + 模式。
     *
     * 只用 channelId 做 key 会串：切「中转/直连」、换服务器、解绑重绑之后，
     * 同一个频道号会命中上一种上下文的旧 URL 和请求头——表现为「切了中转
     * 还在直连」「播的是旧服务器的内容」。
     */
    val identity: String = "",
    val channelId: Long = 0,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val mode: String = "",
    val ua: String = "",
    /** 签名过期时间（Unix 秒） */
    val expires: Long = 0,
) {
    /**
     * 这条缓存现在还能不能用。
     *
     * 抽成纯函数（原来埋在 PlayCacheStore.get 里）是为了能单测边界：
     * 签名剩余不足 [MIN_REMAIN_SEC] 就不能用——否则会出现「刚起播地址就过期，
     * 服务端 403，三个内核全失败」这种极难复现的现象。
     */
    fun isUsable(nowSec: Long, minRemainSec: Long = MIN_REMAIN_SEC): Boolean =
        url.isNotBlank() && expires > nowSec + minRemainSec

    companion object {
        /** 签名剩余不足这个秒数就不用缓存——留足余量，避免刚起播就过期。 */
        const val MIN_REMAIN_SEC = 60L
    }
}

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
    /**
     * 这条线路的完整请求头（服务端已清洗过：去掉了 Authorization / Cookie /
     * X-Trim-* 等身份头与逐跳头）。
     *
     * R09：以前只解码 ua，遇到"要求 Referer 才给流"的源，direct 直连必然 403，
     * 而 relay 却因为服务端带了头而正常——表现为"直连打不开、中转能看"。
     * 现在把整份头交给各播放内核。
     */
    val headers: Map<String, String> = emptyMap(),
) {
    /**
     * 交给播放内核的最终请求头。
     *
     * 内核各自只支持一部分头（Exo 支持任意自定义头，VLC/mpv 走 options），
     * 但至少先把 UA 和 Referer 这类最常见的校验头带全。
     * ua 字段是历史字段，headers 里已有 User-Agent 时以 headers 为准。
     */
    fun playHeaders(defaultUA: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && !v.contains('\n') && !v.contains('\r')) {
                out[k] = v
            }
        }
        if (out.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            out["User-Agent"] = ua.ifBlank { defaultUA }
        }
        return out
    }
}

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
    /**
     * 频道列表指纹。变了就说明服务端增删/启用停用/改名过频道，
     * 客户端该重新拉一次列表了。
     *
     * 为什么需要它：以前只在打开播放页时 bootstrap 一次，
     * 管理员在服务端启用了新频道，电视端永远看不到，只能杀进程重进。
     */
    @SerializedName("channels_version") val channelsVersion: String = "",
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

/**
 * 服务端 /api/v1/tv/remote/targets 的返回结构。
 *
 * 注意必须放在 data 包里：proguard 只 keep 了 com.litv.tv.data.**，
 * 放在 UI 类内部会被 R8 混淆掉构造函数，Gson 反序列化直接崩。
 */
data class RemoteTarget(
    val id: Long = 0,
    val name: String = "",
    val platform: String = "",
    val model: String = "",
    val online: Boolean = false,
)

/** /tv/bootstrap 里带的源清单（只有 id 和名称） */
data class SourceBrief(
    val id: Long = 0,
    val name: String = "",
)
