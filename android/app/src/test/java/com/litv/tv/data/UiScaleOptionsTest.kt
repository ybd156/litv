package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 界面缩放档位与错误卡片模型（纯逻辑，进 CI）。 */
class UiScaleOptionsTest {

    @Test
    fun `未知或空值退回中档`() {
        assertEquals(UiScaleOptions.Scale.MEDIUM, UiScaleOptions.from(null))
        assertEquals(UiScaleOptions.Scale.MEDIUM, UiScaleOptions.from(""))
        assertEquals(UiScaleOptions.Scale.MEDIUM, UiScaleOptions.from("xl"))
        // 大小写/空白容错（手改偏好文件时不至于把界面搞成默认以外的样子）
        assertEquals(UiScaleOptions.Scale.LARGE, UiScaleOptions.from(" L "))
    }

    @Test
    fun `三档字号与行高单调递增`() {
        val scales = UiScaleOptions.all
        assertEquals(3, scales.size)
        for (i in 1 until scales.size) {
            assertTrue(scales[i].fontScale > scales[i - 1].fontScale)
            assertTrue(scales[i].rowHeightDp > scales[i - 1].rowHeightDp)
        }
        // 中档必须是 1.0：设计稿基准，别在升级时整体放大/缩小
        assertEquals(1.0f, UiScaleOptions.Scale.MEDIUM.fontScale, 0.0001f)
    }

    @Test
    fun `字号换算跟随档位`() {
        assertEquals(14f, UiScaleOptions.scaled(14f, "m"), 0.01f)
        assertTrue(UiScaleOptions.scaled(14f, "l") > 14f)
        assertTrue(UiScaleOptions.scaled(14f, "s") < 14f)
    }

    @Test
    fun `落盘 key 唯一`() {
        assertEquals(UiScaleOptions.keys.size, UiScaleOptions.keys.distinct().size)
        assertFalse(UiScaleOptions.keys.contains(""))
    }
}

/** 播放错误卡片：动作顺序与出现条件。 */
class PlaybackErrorCardTest {

    @Test
    fun `直连失败给四个动作且顺序固定`() {
        val m = PlaybackErrorCard.build(
            PlaybackErrorCard.Kind.DIRECT_FAILED,
            reasonText = "源站中断了 TLS 握手",
            hasNextLine = true,
            directFailed = true,
        )
        assertEquals("这条线路连不上", m.title)
        assertEquals("源站中断了 TLS 握手", m.subtitle)
        assertEquals(
            listOf(
                PlaybackErrorCard.Action.RETRY,
                PlaybackErrorCard.Action.NEXT_LINE,
                PlaybackErrorCard.Action.RELAY,
                PlaybackErrorCard.Action.CLOSE,
            ),
            m.actions,
        )
        // 焦点默认落在第一个动作 —— 必须是"重试"，不能是"返回"
        assertEquals(PlaybackErrorCard.Action.RETRY, m.actions.first())
    }

    @Test
    fun `非直连失败不给中转动作`() {
        val m = PlaybackErrorCard.build(
            PlaybackErrorCard.Kind.ALL_ENGINES_FAILED, "所有播放内核都失败了",
        )
        assertFalse(m.actions.contains(PlaybackErrorCard.Action.RELAY))
        assertEquals(PlaybackErrorCard.Action.RETRY, m.actions.first())
        assertEquals(PlaybackErrorCard.Action.CLOSE, m.actions.last())
    }

    @Test
    fun `没有下一条线路就不给换线路`() {
        val m = PlaybackErrorCard.build(
            PlaybackErrorCard.Kind.LINE_INVALID, "线路地址不可用", hasNextLine = false,
        )
        assertFalse(m.actions.contains(PlaybackErrorCard.Action.NEXT_LINE))
        assertEquals("这条线路已失效", m.title)
    }

    @Test
    fun `原因为空时给兜底文案而不是空白`() {
        val m = PlaybackErrorCard.build(PlaybackErrorCard.Kind.DIRECT_FAILED, "")
        assertTrue(m.subtitle.isNotBlank())
    }
}
