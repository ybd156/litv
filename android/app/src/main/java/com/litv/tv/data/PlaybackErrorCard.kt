package com.litv.tv.data

/**
 * 播放错误卡片的**内容模型**（纯函数，可 JVM 单测）。
 *
 * 原来的问题：三处错误各写各的文案与按钮，用户在电视上看到的是一串 SDK 异常；
 * 而且"重试"到底重试什么、"换线路"有没有可换，都要点进去才知道。
 *
 * 现在统一成一张卡片：标题一句人话、副标题一句原因、动作按优先级排好，
 * 焦点默认落在第一个动作（重试）上，BACK 关卡片而不是退出播放页。
 */
object PlaybackErrorCard {

    enum class Kind {
        /** 直连不通（源站在当前网络不可达 / DNS 污染 / SNI 被重置） */
        DIRECT_FAILED,

        /** 所有内核都播不了（编码不支持、线路失效） */
        ALL_ENGINES_FAILED,

        /** 这条线路明确失效（上游 404/403、服务端标记下线） */
        LINE_INVALID,
    }

    enum class Action(val label: String) {
        RETRY("重试"),
        NEXT_LINE("换下一条线路"),
        RELAY("用 NAS 中转试试"),
        CLOSE("返回"),
    }

    data class Model(val title: String, val subtitle: String, val actions: List<Action>)

    /**
     * @param reasonText  `directFailureText()` 之类的"人话原因"，不要塞原始异常
     * @param hasNextLine 当前频道是否还有没试过的线路
     * @param directFailed 本次失败是否属于"直连不通"（决定要不要给中转动作）
     */
    fun build(
        kind: Kind,
        reasonText: String,
        hasNextLine: Boolean = false,
        directFailed: Boolean = false,
    ): Model {
        val title = when (kind) {
            Kind.DIRECT_FAILED -> "这条线路连不上"
            Kind.ALL_ENGINES_FAILED -> "这个频道播不了"
            Kind.LINE_INVALID -> "这条线路已失效"
        }
        val subtitle = reasonText.ifBlank {
            when (kind) {
                Kind.DIRECT_FAILED -> "连不上源站"
                Kind.ALL_ENGINES_FAILED -> "所有播放内核都失败了"
                Kind.LINE_INVALID -> "线路地址不可用"
            }
        }

        // 动作按优先级：重试 → 换线路 → 中转（仅直连失败）→ 返回。
        // 「返回」永远在最后，它是"退路"而不是主操作。
        val actions = ArrayList<Action>(4)
        actions.add(Action.RETRY)
        if (hasNextLine) actions.add(Action.NEXT_LINE)
        if (kind == Kind.DIRECT_FAILED || directFailed) actions.add(Action.RELAY)
        actions.add(Action.CLOSE)
        return Model(title, subtitle, actions)
    }
}
