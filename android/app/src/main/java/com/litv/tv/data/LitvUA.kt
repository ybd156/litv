package com.litv.tv.data

/**
 * 所有出站请求的默认 User-Agent。
 *
 * 为什么必须只有一份：
 * 源站普遍按 UA 判来源。同一台设备在「取流 / 拉节目单 / 拉台标」三条路径上
 * 如果报的 UA 不一样，会被当成不同客户端——表现为"列表能拉到、流却 403"
 * 这类极难归因的问题。
 *
 * 这里统一成一份，并且**修掉了一次真实漂移**：VLC 与 mpv 的兜底各自写死过
 * `"LITV/1.0"`（少了 " (Android)"），与 Exo / UI 层报的不是同一个值。
 */
object LitvUA {
    const val ANDROID = "LITV/1.0 (Android)"
}
