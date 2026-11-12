#!/bin/bash
# R18 回归测试：向导写入的 YAML 标量必须能被真正解析回原值。
#
# 缺陷：install_callback / config_callback 曾把密码直接塞进 "..."，
# 合法密码里只要有双引号或反斜杠，写出来的 YAML 就变成另一个值，
# 或者直接解析失败导致服务起不来——而且要到重启才发现。
#
# 判据：对每种"恶劣但合法"的密码，用脚本里真实的 yaml_dq 编码，
# 再用 YAML 解析器读回来，必须与原文完全一致。
set -uo pipefail

HERE="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALL_CB="${HERE}/packaging/fnos/cmd/install_callback"
CONFIG_CB="${HERE}/packaging/fnos/cmd/config_callback"

fail=0
say() { printf '%s\n' "$*"; }
ok()  { printf '  [ ok ] %s\n' "$*"; }
bad() { printf '  [FAIL] %s\n' "$*"; fail=1; }

say "== R18 YAML 标量编码验证 =="

# 顶格 } 才算函数结束；循环里的缩进 } 不算，所以 sed 范围安全。
extract_fn() {
    sed -n '/^yaml_dq() {/,/^}/p' "$1"
}

FN_INSTALL="$(extract_fn "${INSTALL_CB}")"
FN_CONFIG="$(extract_fn "${CONFIG_CB}")"

[ -n "${FN_INSTALL}" ] || bad "install_callback 里没有 yaml_dq 函数"
[ -n "${FN_CONFIG}" ]  || bad "config_callback 里没有 yaml_dq 函数"

if [ "${FN_INSTALL}" != "${FN_CONFIG}" ]; then
    bad "两个脚本里的 yaml_dq 实现不一致（一处修好另一处仍是坏的）"
elif [ -n "${FN_INSTALL}" ]; then
    ok "两个脚本的 yaml_dq 实现一致"
fi

eval "${FN_INSTALL}"

if ! declare -f yaml_dq >/dev/null 2>&1; then
    bad "yaml_dq 未能加载，后续验证无意义"
    say ""
    say "结论: 失败"
    exit 1
fi

CASES=(
    'simple-password'
    'has"quote'
    'has\backslash'
    'a"b\c"d\e'
    'ampersand&and$dollar'
    '中文密码测试'
    'colon: space'
    '#hash-start'
    '@at~tilde'
    'quote"mid"dle'
)

have_py=0
if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
    have_py=1
fi

for c in "${CASES[@]}"; do
    if ! enc="$(yaml_dq "$c")"; then
        bad "编码失败: ${c}"
        continue
    fi
    case "${enc}" in
        \"*\") ;;
        *) bad "编码结果不是双引号标量: ${c} -> ${enc}"; continue ;;
    esac

    if [ "${have_py}" = "1" ]; then
        got="$(ENC="${enc}" python3 -c '
import os, sys
import yaml
enc = os.environ["ENC"]
try:
    v = yaml.safe_load("admin_password: " + enc + "\n")["admin_password"]
except Exception as e:
    sys.stderr.write("PARSE_ERROR:" + str(e)); sys.exit(1)
sys.stdout.write(v)
' 2>&1)" && rc=0 || rc=$?
        if [ "${rc}" != "0" ]; then
            bad "YAML 解析失败: ${c} -> ${enc} (${got})"
        elif [ "${got}" != "${c}" ]; then
            bad "往返不一致: 原文[${c}] 回读[${got}] 编码[${enc}]"
        else
            ok "往返一致: ${c}"
        fi
    else
        ok "编码为合法双引号标量: ${c}"
    fi
done

if yaml_dq "$(printf 'a\nb')" >/dev/null 2>&1; then
    bad "含换行的值没有被拒绝"
else
    ok "含换行的值被拒绝"
fi

say ""
if [ "${fail}" = "0" ]; then
    say "结论: 通过 —— 向导写入的密码/密钥能被正确解析回原值"
    exit 0
fi
say "结论: 失败 —— 特殊字符会破坏配置文件"
exit 1
