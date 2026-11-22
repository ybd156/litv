package com.litv.tv.ui

import com.litv.tv.data.LitvUA
import android.app.Activity
import android.widget.Toast
import com.litv.tv.data.Api
import com.litv.tv.data.Channel
import com.litv.tv.data.EpgProgram
import com.litv.tv.player.PlayerManager

/**
 * 节目单上的两个动作：预约、回看。
 *
 * 电视端和手机端都走这里，避免两边语义分叉
 * （比如电视预约成功会刷新成「已预约」，手机却不刷新）。
 */
object EpgActions {

    fun reserve(
        activity: Activity,
        api: Api,
        ch: Channel,
        p: EpgProgram,
        onDone: () -> Unit = {},
    ) {
        api.createReminder(
            ch.id, p.title, p.startAt,
            onOk = {
                activity.runOnUiThread {
                    Toast.makeText(activity, "已预约：" + p.title, Toast.LENGTH_SHORT).show()
                    onDone()
                }
            },
            onErr = { msg ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "预约失败：$msg", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    /** 回看：先问服务端这个台有没有时移缓冲，有就拿地址播。 */
    fun replay(
        activity: Activity,
        api: Api,
        player: PlayerManager,
        ch: Channel,
        engine: String,
        p: EpgProgram? = null,
    ) {
        api.timeshift(ch.id,
            onOk = { info ->
                if (!info.enabled || info.availableSeconds <= 0) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "这个台暂时没有可回看的缓冲", Toast.LENGTH_SHORT).show()
                    }
                    return@timeshift
                }
                val abs = api.absolute(info.url)
                activity.runOnUiThread {
                    player.play(
                        ch.id, abs,
                        mapOf("User-Agent" to LitvUA.ANDROID),
                        engine,
                    )
                    p?.let {
                        Toast.makeText(activity, "回看：" + it.title, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onErr = { msg ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "回看不可用：$msg", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
