#!/bin/bash
# =============================================================================
# 本地生命周期冒烟测试（不需要飞牛机器）
#
# 把打好的 .fpk 解到临时目录，模拟 fnOS 安装后的 /var/apps/litv 布局，
# 用真实 TRIM_* 环境变量把整条链路跑一遍：
#   install_callback → start → 网关 socket 探活 → 重复启动 → 崩溃自愈
#   → 日志轮转 → config_callback → stop → uninstall_callback → upgrade_callback
#
# 用法：./tools/smoke-test.sh dist/litv_x86_v1.0.0.fpk
# 注意：会占用 19527/19528 端口，跑完自动清理；需要 curl。
# =============================================================================

set -u
FPK="${1:-}"
[ -n "${FPK}" ] && [ -f "${FPK}" ] || { echo "用法: $0 <xxx.fpk>"; exit 1; }
FPK="$(cd "$(dirname "${FPK}")" && pwd)/$(basename "${FPK}")"

WORK="${SMOKE_DIR:-/tmp/litv-smoke}"

# 自动挑空闲端口：开发机上 9527/19527 经常被别的调试进程占着，
# 硬编码端口会让冒烟测试莫名其妙地失败在 bind 上。
pick_port() {
    local p="$1" i=0
    while [ "${i}" -lt 200 ]; do
        if ! ss -ltn 2>/dev/null | grep -qE "[:.]${p}[[:space:]]"; then
            printf '%s' "${p}"
            return 0
        fi
        p=$((p + 1))
        i=$((i + 1))
    done
    printf '%s' "$1"
}
PORT_A="$(pick_port "${SMOKE_PORT_A:-19527}")"
PORT_B="$(pick_port $((PORT_A + 1)))"

rm -rf "${WORK}"; mkdir -p "${WORK}"
cd "${WORK}" || exit 1

pass=0; fail=0; warn=0
ok()   { printf '\033[32m  PASS\033[0m %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '\033[31m  FAIL\033[0m %s\n' "$*"; fail=$((fail+1)); }
known(){ printf '\033[33m  KNOWN\033[0m %s\n' "$*"; warn=$((warn+1)); }
step() { printf '\n\033[36m== %s\033[0m\n' "$*"; }

step "1. 解包 .fpk（模拟安装落地）"
tar xzf "${FPK}" || exit 1
# 注意：app.tgz 内部**没有** app/ 顶层目录，直接就是 server/ ui/ www/ config/，
# 安装后整体展开到 /var/apps/litv/target/ 下。这里手动还原成 target/。
mkdir -p target
tar xzf app.tgz -C target || exit 1
rm -f app.tgz
mkdir -p var etc
[ -d cmd ] && ok "cmd/ 已落地" || bad "cmd/ 缺失"
[ -f target/server/config.yaml.template ] && ok "target/server/config.yaml.template 已落地" || bad "模板缺失"
[ -d target/www ] && ok "target/www 已落地（$(find target/www -type f | wc -l) 个文件）" || bad "target/www 缺失"
[ -f target/ui/config ] && ok "target/ui/config 已落地" || bad "target/ui/config 缺失"

export TRIM_APPNAME=litv
export TRIM_APPDEST="${WORK}/target"
export TRIM_PKGVAR="${WORK}/var"
export TRIM_PKGETC="${WORK}/etc"
export TRIM_TEMP_LOGFILE="${WORK}/temp.logfile"
export TRIM_USERNAME="$(id -un)"
export TRIM_GROUPNAME="$(id -gn)"
export TRIM_SYS_VERSION="1.1.3100"

step "2. install_callback（向导：port=${PORT_A}）"
wizard_port="${PORT_A}" wizard_admin_password='litvtest123' wizard_data_path="${WORK}/library" \
    bash cmd/install_callback | sed 's/^/    /'
[ -f etc/config.yaml ] && ok "config.yaml 已生成" || bad "config.yaml 未生成"
grep -q "^  port: ${PORT_A}" etc/config.yaml && ok "端口写入正确" || bad "端口写入错误"
grep -qE '^  secret: "[0-9a-f]{64}"' etc/config.yaml && ok "auth.secret 是 64 位十六进制随机串" || bad "auth.secret 异常"
grep -q '^  admin_password: "litvtest123"' etc/config.yaml && ok "管理员密码写入正确" || bad "管理员密码写入错误"
grep -q 'CHANGE_ME' etc/config.yaml && bad "仍有 CHANGE_ME 占位值" || ok "无残留占位值"
[ "$(cat var/server_port)" = "${PORT_A}" ] && ok "server_port 落盘" || bad "server_port 错误"
[ -d var/data ] && ok "var/data 已创建（权限 $(stat -c %a var/data)）" || bad "var/data 未创建"

step "3. start（含 socket 就绪等待）"
bash cmd/main start; rc=$?
[ "${rc}" = "0" ] && ok "start 退出码 0" || bad "start 退出码 ${rc}"
bash cmd/main status >/dev/null; [ "$?" = "0" ] && ok "status 退出码 0（运行中）" || bad "status 非 0"
[ -S target/app.sock ] && ok "app.sock 已创建（$(stat -c '%a' target/app.sock)）" || bad "app.sock 不存在"

step "4. 网关链路（socket + /app/litv 前缀 + X-Trim 身份）"
# 真实服务端的 API 前缀是 /api/v1/（不是 /api/），健康检查在 /api/v1/health
code=$(curl -s -o /dev/null -w '%{http_code}' --unix-socket target/app.sock http://localhost/app/litv/api/v1/health)
[ "${code}" = "200" ] && ok "GET /app/litv/api/v1/health -> 200" || bad "health 返回 ${code}"
body=$(curl -s --unix-socket target/app.sock http://localhost/app/litv/api/v1/health)
echo "    body: ${body}"
body=$(curl -s --unix-socket target/app.sock \
    -H 'X-Trim-Userid: 1000' -H 'X-Trim-Username: admin' -H 'X-Trim-Isadmin: true' \
    http://localhost/app/litv/api/v1/system/me)
echo "    me:   ${body}"
echo "${body}" | grep -q '"user_id":"1000"' && ok "网关注入的 X-Trim-Userid 已透传到服务端" || bad "身份 Header 未透传: ${body}"
echo "${body}" | grep -q '"via_gateway":true' && ok "服务端识别为网关来源（via_gateway=true）" || bad "服务端未识别网关来源"

# 管理台首页：服务端把前端 go:embed 进二进制，经网关访问 /app/litv/ 应当直接出 HTML。
# 已知缺陷（server/internal/web/web.go）：根路径被改写成 /index.html 后交给
# http.FileServer，而 FileServer 会把 */index.html 301 到 ./，形成重定向死循环。
final=$(curl -sL --unix-socket target/app.sock -o /dev/null -w '%{http_code}:%{num_redirects}' http://localhost/app/litv/)
code="${final%%:*}"; hops="${final##*:}"
if [ "${code}" = "200" ]; then
    ok "管理台首页 /app/litv/ -> 200"
elif [ "${hops}" -ge 5 ]; then
    known "管理台首页 /app/litv/ 301 重定向死循环（${hops} 跳）——服务端 web.Handler 缺陷，与打包无关"
else
    bad "首页返回 ${code}（重定向 ${hops} 次）"
fi
code=$(curl -s -o /dev/null -w '%{http_code}' --unix-socket target/app.sock http://localhost/app/litv/assets/index.js)
case "${code}" in
    200 | 404) ok "静态资源路由可达（assets/index.js -> ${code}）" ;;
    *) bad "静态资源异常（${code}）" ;;
esac

step "5. TCP 直连端口（原生客户端入口）"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT_A}/api/v1/health")
[ "${code}" = "200" ] && ok "GET http://127.0.0.1:${PORT_A}/api/v1/health -> 200" || bad "TCP 入口返回 ${code}"

step "6. 重复 start（防双实例）"
before=$(pgrep -f "target/server/litvd" 2>/dev/null | wc -l)
bash cmd/main start >/dev/null; rc=$?
after=$(pgrep -f "target/server/litvd" 2>/dev/null | wc -l)
[ "${rc}" = "0" ] && ok "重复 start 返回 0（幂等）" || bad "重复 start 返回 ${rc}"
[ "${before}" = "${after}" ] && ok "进程数未增加（${after} 个）" || bad "出现双实例：${before} -> ${after}"

step "7. 崩溃自愈（kill -9 留下残留 socket）"
kill -9 "$(cat var/litvd.pid)" 2>/dev/null; sleep 0.5
[ -e target/app.sock ] && echo "    残留 socket 存在（正是社区翻车点）"
bash cmd/main status >/dev/null; [ "$?" = "3" ] && ok "status 退出码 3（未运行）" || bad "status 未返回 3"
bash cmd/main start >/dev/null; [ "$?" = "0" ] && ok "残留 socket 下仍能启动（rm -f 生效）" || bad "残留 socket 导致启动失败"
[ -S target/app.sock ] && ok "socket 重新就绪" || bad "socket 未恢复"

step "8. 日志轮转（>10MB 转成 .1）"
bash cmd/main stop >/dev/null 2>&1
head -c 11000000 /dev/zero | tr '\0' 'x' >>var/litvd.log
size_before=$(stat -c %s var/litvd.log)
bash cmd/main start >/dev/null 2>&1
[ -f var/litvd.log.1 ] && ok "超过 ${size_before} 字节后已轮转为 litvd.log.1" || bad "未轮转"
[ "$(stat -c %s var/litvd.log)" -lt 10485760 ] && ok "新日志从 0 开始（$(stat -c %s var/litvd.log) 字节）" || bad "新日志未重置"

step "9. config_callback（改端口 ${PORT_B} + 开转码）"
wizard_port="${PORT_B}" wizard_admin_password='' wizard_transcode_enabled=true bash cmd/config_callback | sed 's/^/    /'
grep -q "^  port: ${PORT_B}" etc/config.yaml && ok "端口已更新为 ${PORT_B}" || bad "端口未更新"
grep -q '^  enabled: true' etc/config.yaml && ok "转码开关已置 true" || bad "转码开关未更新"
[ "$(cat var/server_port)" = "${PORT_B}" ] && ok "server_port 已同步" || bad "server_port 未同步"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT_B}/api/v1/health")
[ "${code}" = "200" ] && ok "服务已在新端口 ${PORT_B} 响应（说明重启成功）" || bad "新端口无响应（${code}）"

step "10. stop / status"
bash cmd/main stop >/dev/null; [ "$?" = "0" ] && ok "stop 退出码 0" || bad "stop 失败"
bash cmd/main status >/dev/null; [ "$?" = "3" ] && ok "status 退出码 3" || bad "status 未返回 3"
[ -e target/app.sock ] && bad "stop 后 socket 残留" || ok "stop 后 socket 已清理"
[ -e var/litvd.pid ] && bad "stop 后 PID 文件残留" || ok "stop 后 PID 文件已清理"

step "11. uninstall_callback（保留数据）"
wizard_data_action=keep bash cmd/uninstall_callback | sed 's/^/    /'
[ -d var ] && ok "var 目录保留" || bad "var 被误删"
[ -n "$(ls var/data/*.db 2>/dev/null)" ] && ok "数据库保留" || echo "    （本次运行未生成 db 文件，跳过）"

step "12. upgrade_callback（升级后自动重启）"
bash cmd/upgrade_callback | sed 's/^/    /'
bash cmd/main status >/dev/null; [ "$?" = "0" ] && ok "升级回调后服务已自动拉起" || bad "升级后未拉起"
bash cmd/main stop >/dev/null 2>&1

step "13. uninstall_callback（删除数据，安全白名单）"
# 先把日志留一份：delete 模式会把 var/ 清空，否则第 14 步就没日志可看了
cp -f var/litvd.log "${WORK}/litvd.log.before-uninstall" 2>/dev/null || true
mkdir -p var/data && touch var/data/litv.db
wizard_data_action=delete bash cmd/uninstall_callback | sed 's/^/    /'
[ -f var/data/litv.db ] && bad "delete 模式下数据库仍在" || ok "delete 模式已清空数据"

step "14. 服务端日志（最后 10 行）"
tail -n 10 "${WORK}/litvd.log.before-uninstall" 2>/dev/null | sed 's/^/    /' || echo "    (无日志)"

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, %d 已知问题(非打包层), ' "${pass}" "${warn}"
if [ "${fail}" -gt 0 ]; then printf '\033[31m%d 失败\033[0m\n' "${fail}"; else printf '0 失败\n'; fi
echo "工作目录: ${WORK}（可删）"
[ "${fail}" -eq 0 ]
