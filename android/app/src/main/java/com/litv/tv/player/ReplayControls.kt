package com.litv.tv.player

/**
 * 回看 / 时移的播放头与控制步长（纯逻辑，不碰 Android）。
 *
 * 为什么自己算播放头，而不是直接读内核上报的位置：
 *   · VLC 播 HLS 时 `player.time` 经常不前进（见 [EngineStats.positionMs] 注释），
 *     拿它画进度条会一直停在 0%；
 *   · Exo 每加载一次播放列表位置就回落一次（真机日志 2760 -> 1761）；
 *   · mpv 只有部分流给 `time-pos`。
 * 三个内核三种口径，进度条必然抖。回看是**有限时长**的内容（节目 30/45 分钟，
 * 时移就是服务端缓冲 N 分钟），用「墙钟 + 暂停累计 + seek 基准」算出来的
 * 播放头既单调又和用户看到的画面同速，才适合当 UI 依据。
 *
 * 边界：这个类是**显示与操作模型**，不负责真正让内核跳转；
 * seek 之后由调用方把同一个目标值交给 `PlayerManager.seekTo()`，两边保持同源。
 */
data class ReplayWindow(
    /** 可回看的总时长（毫秒）。<=0 表示这不是可回看内容。 */
    val durationMs: Long,
    /** OSD 上显示的节目名（可空）。 */
    val title: String = "",
)

object ReplaySteps {
    /** 左右方向键一次跳多少。10 秒是遥控器上最顺手的粒度（爱奇艺/云视听一致）。 */
    const val KEY_STEP_MS = 10_000L

    /** 控制条上的「快退/快进」按钮跳多少。按钮就是要比按键狠一点，30 秒是通行值。 */
    const val BUTTON_STEP_MS = 30_000L

    /** 距离末尾多近算「快看完了」，此时提示回到直播。 */
    const val NEAR_END_MS = 5_000L

    fun clamp(targetMs: Long, durationMs: Long): Long {
        // 时长未知（<=0）时只保证不为负：让调用方仍能 seek，只是不设上限。
        if (durationMs <= 0L) return targetMs.coerceAtLeast(0L)
        return targetMs.coerceIn(0L, durationMs)
    }
}

/**
 * 回看播放头。所有方法都接收 `nowMs`（elapsedRealtime），
 * 这样单测不需要模拟时间，也不会因为系统时间被改而跳变。
 */
class ReplayClock(val durationMs: Long) {

    private var anchorAtMs = 0L
    private var anchorPosMs = 0L
    private var pausedAtMs = -1L

    var positionMs = 0L
        private set

    /** 是否在走（没暂停）。未开始时也算「在走」，调用方负责先 reset。 */
    val running: Boolean get() = pausedAtMs < 0L

    fun reset(nowMs: Long, positionMs: Long = 0L) {
        anchorAtMs = nowMs
        anchorPosMs = ReplaySteps.clamp(positionMs, durationMs)
        this.positionMs = anchorPosMs
        pausedAtMs = -1L
    }

    /** 按墙钟推进播放头，返回当前值。暂停时原样返回。 */
    fun tick(nowMs: Long): Long {
        if (pausedAtMs >= 0L) return positionMs
        positionMs = ReplaySteps.clamp(anchorPosMs + (nowMs - anchorAtMs), durationMs)
        return positionMs
    }

    fun pause(nowMs: Long) {
        if (pausedAtMs >= 0L) return
        tick(nowMs)
        pausedAtMs = nowMs
    }

    /**
     * 继续。基准点必须重置成「现在 + 当前值」，否则后台/暂停那段时间
     * 会在恢复的瞬间被算成"播过去了"，画面却还在原处——进度条直接跳到末尾。
     */
    fun resume(nowMs: Long) {
        if (pausedAtMs < 0L) return
        anchorAtMs = nowMs
        anchorPosMs = positionMs
        pausedAtMs = -1L
    }

    fun seekTo(nowMs: Long, targetMs: Long): Long {
        positionMs = ReplaySteps.clamp(targetMs, durationMs)
        anchorAtMs = nowMs
        anchorPosMs = positionMs
        return positionMs
    }

    fun seekBy(nowMs: Long, deltaMs: Long): Long {
        tick(nowMs)
        return seekTo(nowMs, positionMs + deltaMs)
    }

    val progressPercent: Int
        get() {
            if (durationMs <= 0L) return 0
            return ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100)
        }

    /** 已经播到末尾附近，可以提示「回到直播」。 */
    val nearEnd: Boolean
        get() = durationMs > 0L && positionMs >= durationMs - ReplaySteps.NEAR_END_MS
}

object ClockText {
    /**
     * 毫秒 -> `mm:ss` / `h:mm:ss`。
     *
     * 不用 String.format：设备语言是阿拉伯语/波斯语时 `%d` 会输出本地数字，
     * OSD 上会变成看不懂的符号（这个仓库已经在别处吃过本地化的亏）。
     */
    fun fmt(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        fun two(v: Long) = if (v < 10L) "0$v" else v.toString()
        return if (h > 0L) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}"
    }

    /** 进度条右侧：`已播 / 总长`。 */
    fun pair(positionMs: Long, durationMs: Long): String =
        "${fmt(positionMs)} / ${if (durationMs > 0L) fmt(durationMs) else "--:--"}"
}
