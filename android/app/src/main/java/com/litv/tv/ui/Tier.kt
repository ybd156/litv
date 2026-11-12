package com.litv.tv.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 渲染档位判定：同一份 APK，在不同性能的盒子上走不同渲染路径。
 *
 * 判定依据（按可靠性排序）：
 *  1. ActivityManager.isLowRamDevice —— 系统自己标的，最准
 *  2. 总内存 —— 1GB 盒子和 4GB 电视差别巨大
 *  3. API level —— 老系统缺硬件合成能力
 *
 * 档位差异：
 *  LOW  → 精简 View 列表、无预览小窗、台标降采样、动画关闭
 *  MID  → View 列表 + 预览小窗（可选）、基础动画
 *  HIGH → 完整动效、预览小窗默认开、EPG 网格、更高分辨率台标
 */
enum class Tier {
    LOW, MID, HIGH;

    val hasPreviewWindow: Boolean get() = this != LOW
    val richAnimations: Boolean get() = this == HIGH
    val logoMaxPx: Int
        get() = when (this) {
            LOW -> 96
            MID -> 160
            HIGH -> 240
        }
    /** 频道列表一次加载多少条（低端机分页，避免一次性 inflate 太多 View）。 */
    val pageSize: Int
        get() = when (this) {
            LOW -> 40
            MID -> 80
            HIGH -> 200
        }

    companion object {
        fun detect(context: Context): Tier {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowRam = am?.isLowRamDevice ?: false
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val sdk = Build.VERSION.SDK_INT

            return when {
                lowRam || totalGb < 1.6 || sdk < 23 -> LOW
                totalGb >= 2.5 && sdk >= 28 -> HIGH
                else -> MID
            }
        }
    }
}
