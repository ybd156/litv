package com.litv.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成（纯本地，不联网、不依赖 Google Play 服务）。
 *
 * 场景：电视上输入 IP 地址太痛苦。绑定页显示一个二维码，
 * 手机扫一下直接打开管理台「终端设备」页，点批准即可。
 */
object QrUtil {

    /**
     * @param content 要编码的内容（通常是一个 URL）
     * @param size    输出位图边长（px）
     * @return 失败时返回 null（上层降级为只显示文字）
     */
    fun encode(content: String, size: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // 留 1 个模块的静默区，电视上远距离也扫得进
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }
}
