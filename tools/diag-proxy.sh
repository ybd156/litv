#!/bin/bash
# 查 litvd 进程有没有代理环境变量
PID=$(pgrep -f litvd_x86_64 | head -1)
echo "litvd pid=$PID"
echo "--- 进程环境里的代理变量 ---"
sudo tr '\0' '\n' < /proc/$PID/environ 2>/dev/null | grep -iE "proxy" | head -6
echo "  (以上为空 = 没有代理变量)"
echo
echo "--- 该进程的 ulimit / 网络 ---"
sudo ls -l /proc/$PID/exe 2>/dev/null | head -1
echo
echo "--- 从 shell 环境看 ---"
env | grep -iE "proxy" | head -4
echo "  (空 = shell 也没有)"
echo
echo "--- 用 Go 的方式测同一地址（wget，不带代理）---"
python3 - <<'PY'
import urllib.request, time
url = open('/tmp/errurl.txt').read().strip() if __import__('os').path.exists('/tmp/errurl.txt') else ''
if not url:
    print("  没有 errurl.txt")
else:
    req = urllib.request.Request(url, headers={'User-Agent': 'okHttp/Mod-1.5.0.0'})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            b = r.read(80)
            print(f"  python urllib: {r.status} {len(b)}B {time.time()-t:.2f}s")
            print("  ", b[:60])
    except Exception as e:
        print(f"  python urllib 失败: {type(e).__name__}: {e} ({time.time()-t:.2f}s)")
PY
