"""把 RemoteActivity 里的嵌套 Gson 模型挪到 data 包。

为什么要挪：ProGuard keep 规则只覆盖 com.litv.tv.data.**，
嵌在 UI 类里的 RemoteTarget 会被 R8 混淆成 f1.Q 并删掉构造函数，
运行时 Gson 报「Abstract classes can't be instantiated」——遥控器页直接崩。
挪到 data 包就自动被 keep 规则保护，比放宽 keep 范围更干净。
"""
import io
import re

# 1) 模型挪到 Models.kt
p = "android/app/src/main/java/com/litv/tv/data/Models.kt"
s = io.open(p, encoding="utf-8").read()
if "data class RemoteTarget" not in s:
    s += """
/**
 * 服务端 /api/v1/tv/remote/targets 的返回结构。
 *
 * 注意必须放在 data 包里：proguard 只 keep 了 com.litv.tv.data.**，
 * 放在 UI 类内部会被 R8 混淆掉构造函数，Gson 反序列化直接崩。
 */
data class RemoteTarget(
    val id: Long = 0,
    val name: String = "",
    val platform: String = "",
    val model: String = "",
    val online: Boolean = false,
)
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("Models.kt: 已加 RemoteTarget")

# 2) RemoteActivity 删掉嵌套定义，改用 data 包的
p = "android/app/src/main/java/com/litv/tv/ui/RemoteActivity.kt"
s = io.open(p, encoding="utf-8").read()
old = """    /** 服务端 /tv/remote/targets 的返回结构。 */
    data class RemoteTarget(
        val id: Long = 0,
        val name: String = "",
        val platform: String = "",
        val model: String = "",
        val online: Boolean = false,
    )
"""
if old in s:
    s = s.replace(old, "", 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("RemoteActivity: 已删除嵌套定义")

# 3) 补 import
if "import com.litv.tv.data.RemoteTarget" not in s:
    s = io.open(p, encoding="utf-8").read()
    s = s.replace("import com.litv.tv.R\n", "import com.litv.tv.R\nimport com.litv.tv.data.RemoteTarget\n", 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("RemoteActivity: 已补 import")
