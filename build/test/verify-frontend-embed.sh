#!/bin/bash
# 验证「本次构建的前端确实进了二进制/包」——R11 的验收脚本。
#
# 背景：服务端用 go:embed 打 server/internal/web/www。若构建顺序错了
# （先编 Go、后拷前端），包会带着上一次的前端，而构建一路显示"成功"。
#
# 用法：
#   ./verify-frontend-embed.sh            # 检查仓库内 embed 与 dist 是否一致
#   ./verify-frontend-embed.sh <fpk路径>  # 另外检查 fpk 里那份是否一致
#
# 判据：index.html 引用的资源 hash 必须在 embed 目录里真实存在且内容一致。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EMBED="${REPO_ROOT}/server/internal/web/www"
DIST=""
for cand in "${REPO_ROOT}/admin/dist" "${REPO_ROOT}/web/dist"; do
    [ -d "${cand}" ] && [ -n "$(ls -A "${cand}" 2>/dev/null)" ] && { DIST="${cand}"; break; }
done

fail=0
say() { printf '%s\n' "$*"; }
bad() { printf '  [FAIL] %s\n' "$*"; fail=1; }
ok()  { printf '  [ ok ] %s\n' "$*"; }

say "== R11 前端内嵌验证 =="

if [ -z "${DIST}" ]; then
    say "没有已构建的前端（admin/dist 不存在），跳过一致性检查"
else
    say "dist  : ${DIST}"
    say "embed : ${EMBED}"

    if [ ! -s "${EMBED}/index.html" ]; then
        bad "embed 路径下没有 index.html（二进制里将没有任何管理台）"
    else
        # 逐个比对 index.html 与 dist 的 index.html
        if cmp -s "${EMBED}/index.html" "${DIST}/index.html"; then
            ok "embed/index.html 与 dist/index.html 内容一致"
        else
            bad "embed/index.html 与 dist/index.html 不一致——二进制里是旧前端"
        fi

        # index.html 引用的每个本地资源都必须存在于 embed 目录。
        #
        # 两处防御：
        #   1. 跳过 data: —— 内联 favicon（`src="data:image/svg+xml,…"`）是嵌在
        #      HTML 里的资源，不是文件引用。不加这条会把整条 data URI 当成路径去查。
        #   2. 用 while read 而不是 `for r in ${refs}` —— 后者按空格分词，
        #      而 data URI 里满是空格（`viewBox='0 0 32 32'`），
        #      于是一条引用被拆成十几个假路径，报出「引用了 width='32' 但不存在」。
        refs=$(grep -oE '(src|href)="[^"]+"' "${EMBED}/index.html" \
            | sed -E 's/(src|href)="([^"]+)"/\2/' \
            | grep -vE '^(https?:|data:|//|#)' \
            | sed 's|^/app/litv/||' | sed 's|^/||')
        while IFS= read -r r; do
            [ -n "${r}" ] || continue
            if [ -f "${EMBED}/${r}" ]; then
                ok "资源存在: ${r}"
            else
                bad "index.html 引用了 ${r}，但 embed 目录里没有"
            fi
        done <<< "${refs}"

        # 反向检查：embed 里不应残留 dist 里没有的旧 assets
        if [ -d "${EMBED}/assets" ] && [ -d "${DIST}/assets" ]; then
            stale=$(cd "${EMBED}/assets" && ls -1 2>/dev/null | while read -r f; do
                [ -f "${DIST}/assets/${f}" ] || printf '%s\n' "${f}"
            done)
            if [ -n "${stale}" ]; then
                bad "embed/assets 残留 dist 里没有的文件（旧版本残留）: $(printf '%s' "${stale}" | tr '\n' ' ')"
            else
                ok "embed/assets 无旧版本残留"
            fi
        fi
    fi
fi

# 如果给了 fpk，再检查包内那份
if [ "${1:-}" != "" ]; then
    FPK="$1"
    say ""
    say "检查包内前端: ${FPK}"
    if [ ! -f "${FPK}" ]; then
        bad "找不到 ${FPK}"
    else
        tmpd=$(mktemp -d)
        tar xzf "${FPK}" -O app.tgz 2>/dev/null > "${tmpd}/app.tgz"
        if [ -s "${tmpd}/app.tgz" ]; then
            tar xzf "${tmpd}/app.tgz" -O www/index.html 2>/dev/null > "${tmpd}/pkg-index.html"
            if [ -s "${tmpd}/pkg-index.html" ]; then
                ok "包内有 www/index.html"
            else
                bad "包内没有 www/index.html"
            fi
        else
            bad "fpk 里没有 app.tgz"
        fi
        rm -rf "${tmpd}"
    fi
fi

say ""
if [ "${fail}" = "0" ]; then
    say "结论: 通过 —— 本次构建的前端确实进了二进制"
    exit 0
fi
say "结论: 失败 —— 包里的管理台不是本次构建的产物"
exit 1
