#!/system/bin/sh
# LITV：修复 PPPoE 链路的 MTU 黑洞
#
# 现象：电视网卡 MTU 是 1500，但这条链路实际只能过 1492（PPPoE 拨号的典型值）。
# 路由器没做 MSS 钳制时：
#   - ping 小包       -> 通（所以"全国都能 ping 通"）
#   - TCP 握手 SYN    -> 通（所以能建立连接）
#   - TLS 握手        -> 服务器证书 3~4KB，拆成 1500 字节的包 -> 全丢 -> RST
# 结果就是所有内核都报 "TLS handshake error / connection reset"，
# 直连全失败，而中转（只走局域网）正常。
#
# 为什么不用 ip link set mtu 1492：
#   Android 的 netd 管着接口，root 直接改也会返回 Device or resource busy。
# 所以改成钳制 TCP MSS —— 效果等价，且 netd 不管这一层。
#
# 注意：这是绕过手段。根治要在路由器上把 WAN MTU 设为 1492 并开启 MSS 钳制，
# 那样全屋设备都不用再折腾。

MSS=1452
APPLY_INTERVAL=15
APPLY_TIMES=20

apply_once() {
    for IF in eth0 wlan0; do
        # 接口还不存在就跳过
        ip link show "$IF" >/dev/null 2>&1 || continue
        # -C 已存在则不重复添加
        iptables -t mangle -C OUTPUT -o "$IF" -p tcp --tcp-flags SYN,RST SYN \
            -j TCPMSS --set-mss "$MSS" 2>/dev/null \
        || iptables -t mangle -A OUTPUT -o "$IF" -p tcp --tcp-flags SYN,RST SYN \
            -j TCPMSS --set-mss "$MSS" 2>/dev/null
    done
}

# 放到后台：service.d 里阻塞会拖慢开机
(
    i=0
    while [ "$i" -lt "$APPLY_TIMES" ]; do
        apply_once
        i=$((i + 1))
        sleep "$APPLY_INTERVAL"
    done
) &

exit 0
