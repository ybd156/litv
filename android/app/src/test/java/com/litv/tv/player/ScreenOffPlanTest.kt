package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 息屏处理的判断必须在纯逻辑层锁住：
 * 默认值错了（比如升级后落到「无动作」），表现是「电视黑了但客厅一直在响」，
 * 而这种问题在真机上很难复现，只能靠这里的断言。
 */
class ScreenOffPlanTest {

    @Test
    fun `没设过偏好时默认停播`() {
        assertEquals(ScreenOffAction.Pause, ScreenOffPlan.of(null))
        assertEquals(ScreenOffAction.Pause, ScreenOffPlan.of(""))
    }

    @Test
    fun `认不出的值一律按停播`() {
        assertEquals(ScreenOffAction.Pause, ScreenOffPlan.of("garbage"))
        // 大小写不是我们的写入路径，别猜它的意思
        assertEquals(ScreenOffAction.Pause, ScreenOffPlan.of("NONE"))
    }

    @Test
    fun `明确选了无动作才不停`() {
        assertEquals(ScreenOffAction.None, ScreenOffPlan.of(ScreenOffPlan.VALUE_NONE))
        assertEquals(ScreenOffAction.None, ScreenOffPlan.of(" none "))
    }

    @Test
    fun `设置页的显示值和偏好值能互相还原`() {
        assertEquals("停播", ScreenOffPlan.label(ScreenOffAction.Pause))
        assertEquals("无动作", ScreenOffPlan.label(ScreenOffAction.None))
        assertEquals(ScreenOffPlan.VALUE_PAUSE, ScreenOffPlan.valueOf("停播"))
        assertEquals(ScreenOffPlan.VALUE_NONE, ScreenOffPlan.valueOf("无动作"))
        // 传到奇怪的值也别写坏偏好
        assertEquals(ScreenOffPlan.VALUE_PAUSE, ScreenOffPlan.valueOf("???"))
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
