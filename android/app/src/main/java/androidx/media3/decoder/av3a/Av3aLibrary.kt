// 注意：这个包名是**故意**的。
//
// libav3aJNI.so 里的导出符号是固定形式：
//   Java_androidx_media3_decoder_av3a_Av3aLibrary_av3aGetVersion
// JNI 按「类全名 + 方法名」查找，所以我们的类必须真的叫
// androidx.media3.decoder.av3a.Av3aLibrary，方法签名也必须一致，
// 否则绑定失败（UnsatisfiedLinkError）。
//
// 这个包在 media3 里不存在，不会和官方类冲突。
package androidx.media3.decoder.av3a

import android.util.Log

/**
 * AV3A 解码库的版本信息入口。
 *
 * 用途很实际：库加载成功后调一次 [getVersion]，能返回非空字符串，
 * 就说明 JNI 绑定是通的——不用等整套解码器写完就能验证。
 */
internal object Av3aLibrary {

    private const val TAG = "Av3aLibrary"

    /** 签名必须是 ()Ljava/lang/String; */
    @JvmStatic
    private external fun av3aGetVersion(): String

    /** 库版本；未加载或绑定失败时返回空串，不抛异常。 */
    @JvmStatic
    fun getVersion(): String = try {
        av3aGetVersion() ?: ""
    } catch (t: Throwable) {
        // 典型：UnsatisfiedLinkError（没加载 / 签名不匹配）
        Log.w(TAG, "取版本失败：" + (t.message ?: t.javaClass.simpleName))
        ""
    }
}
