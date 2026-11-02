"""修正上一版插错位置的补丁：
1. 删掉「内核不可用」分支里误插的重试块
2. 把重试逻辑放到真正的超时回调里
"""
import io
import re

p = "android/app/src/main/java/com/litv/tv/player/PlayerManager.kt"
s = io.open(p, encoding="utf-8").read()

# 1) 删掉误插的块
bad = """            if (retried < prefs.retryCount) {
                // 还没到重试上限，同一个内核再来一次（网络抖动能救回来）
                retried++
                android.util.Log.w("PlayerManager", "内核起播超时，重试第 $retried 次")
                startEngine()
                return@Runnable
            }
            retried = 0
"""
if bad in s:
    s = s.replace(bad, "", 1)
    print("已删掉误插块")

# 2) 超时回调里加重试
old = """    private val startTimeout = Runnable {
        if (!prepared) {
            Log.w(TAG, "内核 ${currentKind?.key} 起播超时，降级")
            tryNext("起播超时")
        }
    }"""
new = """    private val startTimeout = Runnable {
        if (prepared) return@Runnable
        // 还没到重试上限就再来一次：网络抖动/慢设备上，重试比直接降级管用
        if (retried < prefs.retryCount) {
            retried++
            Log.w(TAG, "内核 ${currentKind?.key} 起播超时，重试第 $retried 次（上限 ${prefs.retryCount}）")
            startCurrent()
            return@Runnable
        }
        retried = 0
        Log.w(TAG, "内核 ${currentKind?.key} 起播超时，降级")
        tryNext("起播超时")
    }"""
assert old in s, "找不到超时回调"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("重试逻辑已放到超时回调")
