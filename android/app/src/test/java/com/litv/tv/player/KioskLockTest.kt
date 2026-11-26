package com.litv.tv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskLockTest {

    @Test
    fun 换台键与方向键都被拦() {
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_CHANNEL_UP))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_PAGE_UP))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun 退出与设置类按键都被拦() {
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_BACK))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_MENU))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_SETTINGS))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_GUIDE))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_INFO))
        assertTrue(KioskLock.isBlockedKey(KeyEvent.KEYCODE_TV))
    }

    @Test
    fun 数字键都被拦() {
        for (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            assertTrue("keycode $code 应该被拦", KioskLock.isBlockedKey(code))
        }
    }

    @Test
    fun 音量电源与确认键要放行() {
        assertFalse(KioskLock.isBlockedKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(KioskLock.isBlockedKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(KioskLock.isBlockedKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertFalse(KioskLock.isBlockedKey(KeyEvent.KEYCODE_POWER))
        // OK 必须放行：解锁键盘就是靠长按它打开的
        assertFalse(KioskLock.isBlockedKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(KioskLock.isBlockedKey(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun 密码归一化只留数字且补足四位() {
        assertEquals("1234", KioskLock.sanitizePin("1234"))
        assertEquals("1234", KioskLock.sanitizePin("12ab34"))
        assertEquals("0034", KioskLock.sanitizePin("34"))
        assertEquals("3456", KioskLock.sanitizePin("123456"))
        assertEquals(KioskLock.DEFAULT_PIN, KioskLock.sanitizePin(""))
        assertEquals(KioskLock.DEFAULT_PIN, KioskLock.sanitizePin(null))
    }

    @Test
    fun 没设过密码时默认密码可用() {
        // 老版本没有这个键，读出来是空串；此时必须还能用默认密码开锁，
        // 否则用户会被自己的电视锁在门外
        assertTrue(KioskLock.matches("1234", ""))
        assertTrue(KioskLock.matches("1234", null))
        assertFalse(KioskLock.matches("0000", ""))
    }

    @Test
    fun 密码比对两边都归一化() {
        assertTrue(KioskLock.matches(" 12 34 ", "1234"))
        // 多一位按「只取后 4 位」处理，所以 12345 等价于 2345，不等于 1234 ——
        // 多按一下算输错，不该被宽容成正确
        assertFalse(KioskLock.matches("1234", "12345"))
        assertTrue(KioskLock.matches("2345", "12345"))
        assertFalse(KioskLock.matches("1235", "1234"))
    }

    @Test
    fun 锁定提示带频道名() {
        assertTrue(KioskLock.hint("CCTV-1 综合").contains("CCTV-1 综合"))
        assertTrue(KioskLock.hint("").contains("当前频道"))
    }
}
