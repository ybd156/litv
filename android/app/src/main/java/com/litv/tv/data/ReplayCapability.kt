package com.litv.tv.data

/**
 * 「这个台能不能回看」的判定。
 *
 * 为什么需要它：节目单里每一档已播节目都画了「回看」，点了却什么都不发生。
 * 原因不是点击没生效，而是**回看链路只有两条，一条不是哪儿都有**：
 *
 *  1. 内置源（央视频，[YspClient.isYspChannel]）—— 客户端本地代理直连回看 CDN，
 *     不看服务端脸色，任何部署都能回看。
 *  2. 其它源（MCP / 自建源）—— 只能走服务端 `GET /api/v1/tv/timeshift/{id}`。
 *     边缘降级实例（`server.version == "edge"`）没有这个接口，请求必失败。
 *
 * 失败提示又是走 `switchTip` 的，而展开的频道面板盖在它上面 —— 用户看到的就是
 * 「点了没反应」。所以：**判定不能回看的台，节目单不画「回看」角标**。
 *
 * 判定放在纯函数里，是为了能单测：真机/模拟器上「这个角标没画出来」很难自动断言。
 */
object ReplayCapability {

    /** 边缘实例的 `server.version` 取值（见 `server/internal/api/handlers_bootstrap.go`）。 */
    const val EDGE_VERSION = "edge"

    /**
     * @param isBuiltin 这个台是不是内置源（央视频）
     * @param serverVersion bootstrap 里的 `server.version`；还没拿到时传空串
     *
     * 服务端版本未知时按**能回看**处理：宁可多画一个可能失败的角标，
     * 也不要在刚进面板的那几百毫秒里把功能藏起来（那时 bootstrap 往往还没回来）。
     */
    fun canReplay(isBuiltin: Boolean, serverVersion: String): Boolean {
        if (isBuiltin) return true
        if (serverVersion.isBlank()) return true
        return !serverVersion.trim().equals(EDGE_VERSION, ignoreCase = true)
    }
}
