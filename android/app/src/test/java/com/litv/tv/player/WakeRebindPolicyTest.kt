package com.litv.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「息屏唤醒后要不要重绑 VLC 视频输出」的判定。
 *
 * 这条测试挡的是两种退化：
 *   ① 阈值被改成 0 → 每次回前台都重绑 → 画面反复闪
 *   ② 判定里忘了排除非 VLC 内核 → Exo/VLC 都重绑 → Exo 那条本来没问题的路被搞坏
 */
class WakeRebindPolicyTest {

    @Test
    fun `VLC 且离开超过 1_5 秒才重绑`() {
        // ★ 这条是主场景：息屏 30 秒后开屏
        assertTrue(WakeRebindPolicy.shouldRebindVout("vlc", 30_000))
    }

    @Test
    fun `刚好 1_5 秒边界上重绑`() {
        assertTrue(WakeRebindPolicy.shouldRebindVout("vlc", WakeRebindPolicy.MIN_AWAY_MS))
    }

    @Test
    fun `短暂失焦不重绑 避免画面闪`() {
        // 弹个对话框、拉一下通知栏就回来，vout 没坏
        assertFalse(WakeRebindPolicy.shouldRebindVout("vlc", 300))
        assertFalse(WakeRebindPolicy.shouldRebindVout("vlc", WakeRebindPolicy.MIN_AWAY_MS - 1))
    }

    @Test
    fun `非 VLC 内核永远不重绑`() {
        // Exo 自己管 Surface，走这条会把它本来正常的输出打断
        for (k in listOf("exo", "mpv", "avs3", "")) {
            assertFalse("engine=$k 不该重绑", WakeRebindPolicy.shouldRebindVout(k, 60_000))
        }
    }

    @Test
    fun `离开时长未知时保守不重绑`() {
        // awayMs=0 表示没记录到 onPause（例如 Activity 被重建），别猜
        assertFalse(WakeRebindPolicy.shouldRebindVout("vlc", 0))
    }

    @Test
    fun `只要真的息过屏 离开时长为零也重绑`() {
        // 用户快速按两下电源键：间隔可能只有 1 秒，时长判据会漏掉，
        // 但 Surface 照样被回收 → 必须靠 ACTION_SCREEN_OFF 兜住
        assertTrue(WakeRebindPolicy.shouldRebindVout("vlc", 0, hadScreenOff = true))
        assertTrue(WakeRebindPolicy.shouldRebindVout("vlc", 800, hadScreenOff = true))
    }

    @Test
    fun `息过屏但内核不是 VLC 也不重绑`() {
        assertFalse(WakeRebindPolicy.shouldRebindVout("exo", 0, hadScreenOff = true))
    }
}
