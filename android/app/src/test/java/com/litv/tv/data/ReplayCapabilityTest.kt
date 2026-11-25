package com.litv.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayCapabilityTest {

    @Test
    fun `内置源在任何部署上都能回看`() {
        assertTrue(ReplayCapability.canReplay(isBuiltin = true, serverVersion = ReplayCapability.EDGE_VERSION))
        assertTrue(ReplayCapability.canReplay(isBuiltin = true, serverVersion = "0.1.24"))
    }

    @Test
    fun `边缘实例上非内置源不能回看`() {
        assertFalse(ReplayCapability.canReplay(isBuiltin = false, serverVersion = "edge"))
    }

    @Test
    fun `边缘实例的大小写与空白不影响判定`() {
        assertFalse(ReplayCapability.canReplay(isBuiltin = false, serverVersion = "EDGE"))
        assertFalse(ReplayCapability.canReplay(isBuiltin = false, serverVersion = " edge "))
    }

    @Test
    fun `自建服务端上非内置源能回看`() {
        assertTrue(ReplayCapability.canReplay(isBuiltin = false, serverVersion = "0.1.24"))
    }

    @Test
    fun `服务端版本还没拿到时按能回看处理`() {
        assertTrue(ReplayCapability.canReplay(isBuiltin = false, serverVersion = ""))
    }
}
