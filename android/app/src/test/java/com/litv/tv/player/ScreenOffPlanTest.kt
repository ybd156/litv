package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 息屏处理的判断必须在纯逻辑层锁住：
 * 默认值错了（比如升级后落到「无动作」），表现是「电视黑了但客厅一直在响」，
 * 而这种问题在真机上很难复现，只能靠这里的断言。
 *
 * 2026-11-26 用户改口径：息屏不停播，只静音（`Mute` 成为默认）。
 */
class ScreenOffPlanTest {

    @Test
    fun `没设过偏好时默认静音`() {
        assertEquals(ScreenOffAction.Mute, ScreenOffPlan.of(null))
        assertEquals(ScreenOffAction.Mute, ScreenOffPlan.of(""))
    }

    @Test
    fun `认不出的值一律按静音`() {
        assertEquals(ScreenOffAction.Mute, ScreenOffPlan.of("garbage"))
        // 大小写不是我们的写入路径，别猜它的意思
        assertEquals(ScreenOffAction.Mute, ScreenOffPlan.of("NONE"))
    }

    @Test
    fun `旧版本存的 pause 也按静音处理，不需要迁移`() {
        assertEquals(ScreenOffAction.Mute, ScreenOffPlan.of(ScreenOffPlan.VALUE_LEGACY_PAUSE))
        assertEquals(ScreenOffAction.Mute, ScreenOffPlan.of(" pause "))
    }

    @Test
    fun `明确选了无动作才不处理`() {
        assertEquals(ScreenOffAction.None, ScreenOffPlan.of(ScreenOffPlan.VALUE_NONE))
        assertEquals(ScreenOffAction.None, ScreenOffPlan.of(" none "))
    }

    @Test
    fun `设置页的显示值和偏好值能互相还原`() {
        assertEquals("静音", ScreenOffPlan.label(ScreenOffAction.Mute))
        assertEquals("无动作", ScreenOffPlan.label(ScreenOffAction.None))
        assertEquals(ScreenOffPlan.VALUE_MUTE, ScreenOffPlan.valueOf("静音"))
        assertEquals(ScreenOffPlan.VALUE_NONE, ScreenOffPlan.valueOf("无动作"))
        // 传到奇怪的值也别写坏偏好
        assertEquals(ScreenOffPlan.VALUE_MUTE, ScreenOffPlan.valueOf("???"))
    }

    @Test
    fun `偏好里的取值和设置页标签是同一个闭环`() {
        // of 与 valueOf 必须互为逆运算，否则设置页选完不生效
        for (action in ScreenOffAction.values()) {
            assertEquals(action, ScreenOffPlan.of(ScreenOffPlan.valueOf(ScreenOffPlan.label(action))))
        }
    }

    @Test
    fun `短暂停续播，久暂停重回直播边缘`() {
        assertEquals(ScreenOffPlan.ResumeAction.Resume, ScreenOffPlan.onScreenOn(0))
        assertEquals(ScreenOffPlan.ResumeAction.Resume, ScreenOffPlan.onScreenOn(29_999))
        assertEquals(ScreenOffPlan.ResumeAction.RejoinLive, ScreenOffPlan.onScreenOn(30_000))
        assertEquals(
            ScreenOffPlan.ResumeAction.RejoinLive,
            ScreenOffPlan.onScreenOn(10 * 60_000L)
        )
    }

    @Test
    fun `暂停时长算不出来时按续播处理`() {
        // elapsedRealtime 被重置、或 pause 之前就 resume，都会得到非正数；
        // 这时宁可续播一次，也不要无谓地重连一下。
        assertEquals(ScreenOffPlan.ResumeAction.Resume, ScreenOffPlan.onScreenOn(-1))
        assertEquals(ScreenOffPlan.ResumeAction.Resume, ScreenOffPlan.onScreenOn(-600_000L))
    }
}
