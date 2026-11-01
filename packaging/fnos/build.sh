#!/bin/bash
# =============================================================================
# LITV 飞牛 fnOS 一键打包脚本
#
#   ./build.sh                 # 默认 x86 包 → dist/litv_x86_v<ver>.fpk
#   ./build.sh --arch arm      # ARM 包   → dist/litv_arm_v<ver>.fpk
#   ./build.sh --arch all      # 双架构包 → dist/litv_all_v<ver>.fpk
#   ./build.sh --skip-build    # 复用已有二进制，只重新打 .fpk（调包结构时很快）
#   ./build.sh --version 1.2.3 # 临时改版本号（只改打出来的包，不改仓库里的 manifest）
#
# 流程：检查 fnpack → 校验 manifest → 编译/收集二进制 → 拷贝管理台静态文件
#       → 组装干净的 staging 目录 → fnpack build → 改名 + sha256
#
# 为什么要 staging 目录：fnpack 会把"包目录里除 manifest/ICON/cmd/wizard/i18n 之外
# 的一切"塞进 app.tgz（连 .DS_Store 这种都进）。如果在源码目录直接打包，
# .build/、dist/、.gitignore 都会被打进安装包。所以永远在干净的副本里打包。
# =============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${ROOT}/../.." && pwd)"

APPNAME="litv"
BIN_NAME="litvd"
DIST_DIR="${ROOT}/dist"
STAGE="${ROOT}/.build/stage"
BUILD_DIR="${ROOT}/.build"
FNPCK_DOWNLOAD_URL="https://static2.fnnas.com/fnpack/fnpack-1.2.3-linux-amd64"

ARCH="x86"
DO_BUILD=1
VERSION_OVERRIDE=""
FNPCK="${FNPCK:-}"

# ---------------------------------------------------------------- 小工具
c_info() { printf '\033[36m[build]\033[0m %s\n' "$*"; }
c_ok()   { printf '\033[32m[ ok  ]\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m[warn ]\033[0m %s\n' "$*"; }
die()    { printf '\033[31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

# ---------------------------------------------------------------- 参数
while [ $# -gt 0 ]; do
    case "$1" in
        --arch)       ARCH="${2:-}"; shift 2 ;;
        --arch=*)     ARCH="${1#*=}"; shift ;;
        --version)    VERSION_OVERRIDE="${2:-}"; shift 2 ;;
        --version=*)  VERSION_OVERRIDE="${1#*=}"; shift ;;
        --skip-build) DO_BUILD=0; shift ;;
        --fnpack)     FNPCK="${2:-}"; shift 2 ;;
        --fnpack=*)   FNPCK="${1#*=}"; shift ;;
        -h|--help)    usage ;;
        *)            die "未知参数: $1（--help 查看用法）" ;;
    esac
done

case "${ARCH}" in
    x86)   PLATFORM="x86";  GOARCH_LIST="amd64"; BIN_LIST="x86_64"  ;;
    arm)   PLATFORM="arm";  GOARCH_LIST="arm64"; BIN_LIST="aarch64" ;;
    all)   PLATFORM="all";  GOARCH_LIST="amd64 arm64"; BIN_LIST="x86_64 aarch64" ;;
    *)     die "不支持的架构: ${ARCH}（x86|arm|all）" ;;
esac

# ---------------------------------------------------------------- 1. 版本
MANIFEST="${ROOT}/manifest"
[ -f "${MANIFEST}" ] || die "找不到 manifest: ${MANIFEST}"

VERSION="$(grep -E '^[[:space:]]*version[[:space:]]*=' "${MANIFEST}" | head -n1 | cut -d= -f2 | tr -d ' \t\r')"
[ -n "${VERSION}" ] || die "manifest 里没有 version 字段"
[ -n "${VERSION_OVERRIDE}" ] && VERSION="${VERSION_OVERRIDE}"

# ---------------------------------------------------------------- 2. fnpack
find_fnpack() {
    if [ -n "${FNPCK}" ] && [ -x "${FNPCK}" ]; then printf '%s' "${FNPCK}"; return 0; fi
    if [ -x "${REPO_ROOT}/tools/fnpack" ]; then printf '%s' "${REPO_ROOT}/tools/fnpack"; return 0; fi
    if command -v fnpack >/dev/null 2>&1; then command -v fnpack; return 0; fi
    return 1
}

if ! FNPCK="$(find_fnpack)"; then
    c_warn "未找到 fnpack，尝试下载 ${FNPCK_DOWNLOAD_URL}"
    mkdir -p "${REPO_ROOT}/tools"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --connect-timeout 15 -o "${REPO_ROOT}/tools/fnpack" "${FNPCK_DOWNLOAD_URL}" || true
    elif command -v wget >/dev/null 2>&1; then
        wget -O "${REPO_ROOT}/tools/fnpack" "${FNPCK_DOWNLOAD_URL}" || true
    fi
    [ -s "${REPO_ROOT}/tools/fnpack" ] || die "下载失败，请手动下载后放到 tools/fnpack 并 chmod +x：
  ${FNPCK_DOWNLOAD_URL}"
    chmod +x "${REPO_ROOT}/tools/fnpack"
    FNPCK="${REPO_ROOT}/tools/fnpack"
fi

FNPCK_VER="$("${FNPCK}" --help 2>&1 | grep -iE '^Version ' | head -n1 | awk '{print $2}')"
[ -n "${FNPCK_VER}" ] || die "无法执行 fnpack（${FNPCK}），请确认是 Linux amd64 版本且已 chmod +x"
c_ok "fnpack ${FNPCK_VER} (${FNPCK})"

# ---------------------------------------------------------------- 3. manifest 校验
c_info "校验 manifest..."
REQUIRED_KEYS="appname version display_name desc source platform maintainer desktop_uidir desktop_applaunchname"
for k in ${REQUIRED_KEYS}; do
    grep -qE "^[[:space:]]*${k}[[:space:]]*=" "${MANIFEST}" || die "manifest 缺少必需字段: ${k}"
done
# desktop_applaunchname 必须与 app/ui/config 的入口 ID 完全一致，且以 appname 开头
# （后者是 fnpack 的硬校验，前者是官方文档要求，不一致会点开空白页）
LAUNCH_NAME="$(grep -E '^[[:space:]]*desktop_applaunchname[[:space:]]*=' "${MANIFEST}" | head -n1 | cut -d= -f2 | tr -d ' \t\r')"
grep -q "\"${LAUNCH_NAME}\"" "${ROOT}/app/ui/config" || die "desktop_applaunchname=${LAUNCH_NAME} 在 app/ui/config 中不存在（两者必须完全一致）"
case "${LAUNCH_NAME}" in
    "${APPNAME}".*) ;;
    *) die "入口 ID 必须以 ${APPNAME}. 开头（fnpack 硬校验）: ${LAUNCH_NAME}" ;;
esac
[ "${#LAUNCH_NAME}" -le 32 ] || die "入口 ID 超过 32 字符上限: ${LAUNCH_NAME}"
c_ok "manifest 字段完整，入口 ${LAUNCH_NAME}"

# ---------------------------------------------------------------- 4. i18n 引用完整性
# fnpack 只校验"i18n 目录里必须有 en 或 en-US 文件"，不校验 ${section.key} 是否存在。
# 漏一个 key，应用中心就会把字面量 "${install.portLabel}" 显示给用户，非常难看。
# 这里把 manifest / app/ui/config / wizard/* 里引用的 key 全部提出来逐个核对。
has_key() { # file section key
    awk -v sec="[$2]" -v key="$3" '
        /^[[:space:]]*\[/ { cur=$0; gsub(/[[:space:]]/, "", cur); next }
        {
            if (cur == sec) {
                line=$0
                sub(/#.*/, "", line)
                if (line ~ "=") {
                    k=line; sub(/=.*/, "", k); gsub(/[[:space:]]/, "", k)
                    if (k == key) found=1
                }
            }
        }
        END { exit(found ? 0 : 1) }
    ' "$1"
}

c_info "校验 i18n 引用..."
REFS="$(grep -ohE '\$\{[A-Za-z0-9_.]+\}' "${MANIFEST}" "${ROOT}/app/ui/config" "${ROOT}"/wizard/* 2>/dev/null | sed 's/[${}]//g' | sort -u)"
MISSING=0
for ref in ${REFS}; do
    case "${ref}" in
        wizard_*) continue ;;   # 向导运行时变量，不是 i18n key
    esac
    sec="${ref%%.*}"; key="${ref#*.}"
    for lang in zh-CN en-US; do
        if ! has_key "${ROOT}/i18n/${lang}" "${sec}" "${key}"; then
            c_warn "i18n/${lang} 缺少 [${sec}] ${key}（引用自 ${ref}）"
            MISSING=1
        fi
    done
done
[ "${MISSING}" = "0" ] && c_ok "i18n 引用完整（$(printf '%s\n' "${REFS}" | grep -c . || true) 个 key）"

# ---------------------------------------------------------------- 5. 二进制
BIN_DIR="${ROOT}/app/server"
mkdir -p "${BIN_DIR}"

build_stub() { # $1=GOARCH $2=输出名
    local goarch="$1" out="$2"
    command -v go >/dev/null 2>&1 || die "需要 Go 工具链来编译占位二进制（或先用 --skip-build 且手工放入二进制）"
    c_info "编译占位服务端 (GOARCH=${goarch}) ..."
    ( cd "${ROOT}/tools/litvd-stub" && \
      CGO_ENABLED=0 GOOS=linux GOARCH="${goarch}" \
      go build -trimpath -ldflags "-s -w -X main.version=${VERSION}-stub" -o "${BIN_DIR}/${out}" main.go ) \
      || die "占位二进制编译失败"
}

build_real() { # $1=GOARCH $2=输出名
    local goarch="$1" out="$2"
    command -v go >/dev/null 2>&1 || die "需要 Go 工具链"
    local mod_dir="" pkg=""
    if [ -f "${REPO_ROOT}/server/go.mod" ]; then
        mod_dir="${REPO_ROOT}/server"
        # 约定入口：server/cmd/litvd
        if [ -d "${mod_dir}/cmd/litvd" ]; then pkg="./cmd/litvd"; else pkg="."; fi
    else
        die "server/go.mod 不存在，无法编译真实服务端"
    fi
    c_info "编译真实服务端 ${pkg} (GOARCH=${goarch}) ..."
    ( cd "${mod_dir}" && \
      CGO_ENABLED=0 GOOS=linux GOARCH="${goarch}" \
      go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o "${BIN_DIR}/${out}" "${pkg}" ) \
      || die "真实服务端编译失败"
}

REAL_GO_FILES="$(find "${REPO_ROOT}/server" -name '*.go' -not -path '*/vendor/*' 2>/dev/null | head -n1)"
if [ "${DO_BUILD}" = "1" ]; then
    idx=0
    for goarch in ${GOARCH_LIST}; do
        idx=$((idx + 1))
        bin="$(printf '%s\n' ${BIN_LIST} | sed -n "${idx}p")"
        if [ -n "${REAL_GO_FILES}" ]; then
            build_real "${goarch}" "${BIN_NAME}_${bin}"
        else
            c_warn "server/ 下还没有 Go 代码，编译占位二进制 ${BIN_NAME}_${bin}（可安装、可启动、可验证网关）"
            build_stub "${goarch}" "${BIN_NAME}_${bin}"
        fi
    done
else
    c_warn "--skip-build：复用 app/server 下已有的二进制"
fi

for bin in ${BIN_LIST}; do
    f="${BIN_DIR}/${BIN_NAME}_${bin}"
    [ -s "${f}" ] || die "缺少二进制: ${f}"
    c_ok "$(basename "${f}")  $(du -h "${f}" | cut -f1)  $(file -b "${f}" 2>/dev/null | cut -c1-40)"
done

# ---------------------------------------------------------------- 6. 管理台静态文件
WWW_SRC=""
for cand in "${REPO_ROOT}/web/dist" "${REPO_ROOT}/admin/dist" "${REPO_ROOT}/web/build" "${REPO_ROOT}/admin/build"; do
    if [ -d "${cand}" ] && [ -n "$(ls -A "${cand}" 2>/dev/null)" ]; then WWW_SRC="${cand}"; break; fi
done
if [ -n "${WWW_SRC}" ]; then
    c_ok "管理台静态文件: ${WWW_SRC}"
else
    c_warn "没找到已构建的前端（web/dist、admin/dist 均不存在），用占位首页"
fi

# ---------------------------------------------------------------- 7. 组装 staging
c_info "组装 staging 目录..."
rm -rf "${BUILD_DIR}"
mkdir -p "${STAGE}"

# 包根：manifest（按架构改 platform）、ICON、config/、cmd/、wizard/、i18n/
sed "s|^\([[:space:]]*platform[[:space:]]*=\).*|\1 ${PLATFORM}|" "${MANIFEST}" >"${STAGE}/manifest"
if [ -n "${VERSION_OVERRIDE}" ]; then
    sed -i "s|^\([[:space:]]*version[[:space:]]*=\).*|\1 ${VERSION}|" "${STAGE}/manifest"
fi
cp -f "${ROOT}/ICON.PNG" "${ROOT}/ICON_256.PNG" "${STAGE}/"
cp -a "${ROOT}/config" "${ROOT}/cmd" "${ROOT}/wizard" "${ROOT}/i18n" "${STAGE}/"

# 应用负载：app/（fnpack 会整体打进 app.tgz，安装后展开为 target/）
mkdir -p "${STAGE}/app/server" "${STAGE}/app/www"
cp -a "${ROOT}/app/ui" "${STAGE}/app/"
cp -f "${ROOT}/app/server/config.yaml.template" "${STAGE}/app/server/"
for bin in ${BIN_LIST}; do
    cp -f "${BIN_DIR}/${BIN_NAME}_${bin}" "${STAGE}/app/server/"
done

if [ -n "${WWW_SRC}" ]; then
    cp -a "${WWW_SRC}/." "${STAGE}/app/www/"
else
    # 占位首页：保证网关入口打开不是 404/白屏，能立刻看到"服务活着"
    cat >"${STAGE}/app/www/index.html" <<'HTML'
<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>LITV</title>
<style>body{font-family:system-ui,-apple-system,"Noto Sans SC",sans-serif;background:#0f172a;color:#e2e8f0;
display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
.card{max-width:640px;padding:32px;border-radius:16px;background:#1e293b;line-height:1.7}
code{background:#0f172a;padding:2px 6px;border-radius:4px}</style></head>
<body><div class="card">
<h1>LITV 占位页面</h1>
<p>服务端已通过飞牛统一网关正常响应，但管理台前端还没有构建。</p>
<p>构建前端后重新执行 <code>./build.sh</code>，静态文件会被放进 <code>app/www</code>。</p>
<p>健康检查：<a style="color:#22d3ee" href="api/health">api/health</a></p>
</div></body></html>
HTML
fi

# 清掉仓库里的占位文件，别把 .gitkeep 带进安装包
find "${STAGE}" -name '.gitkeep' -delete 2>/dev/null || true
find "${STAGE}" -name '.DS_Store' -delete 2>/dev/null || true
chmod +x "${STAGE}"/cmd/* 2>/dev/null || true
chmod +x "${STAGE}"/app/server/"${BIN_NAME}"_* 2>/dev/null || true

# staging 自检：fnpack 的硬性要求，提前报错比等 fnpack 报错好读
for f in manifest ICON.PNG ICON_256.PNG config/privilege config/resource \
         app/ui/config cmd/main cmd/install_init cmd/install_callback cmd/config_init \
         cmd/config_callback cmd/upgrade_init cmd/upgrade_callback \
         cmd/uninstall_init cmd/uninstall_callback i18n/zh-CN i18n/en-US; do
    [ -e "${STAGE}/${f}" ] || die "staging 缺少 ${f}"
done
[ -d "${STAGE}/wizard" ] || die "staging 缺少 wizard 目录（哪怕空目录也必须存在）"
python3 -c "import json,sys;[json.load(open(p)) for p in sys.argv[1:]]" \
    "${STAGE}/config/privilege" "${STAGE}/config/resource" "${STAGE}/app/ui/config" \
    "${STAGE}"/wizard/* || die "config/ 或 wizard/ 不是合法 JSON"
c_ok "staging 结构自检通过"

# ---------------------------------------------------------------- 8. fnpack build
mkdir -p "${DIST_DIR}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
c_info "执行 fnpack build ..."
( cd "${WORK}" && "${FNPCK}" build -d "${STAGE}" ) || die "fnpack build 失败"

FPK_RAW="$(find "${WORK}" "${STAGE}" -maxdepth 1 -name '*.fpk' 2>/dev/null | head -n1)"
[ -n "${FPK_RAW}" ] || die "fnpack 没有产出 .fpk（在 ${WORK} 与 staging 里都没找到）"

OUT="${DIST_DIR}/${APPNAME}_${ARCH}_v${VERSION}.fpk"
mv -f "${FPK_RAW}" "${OUT}"
( cd "${DIST_DIR}" && sha256sum "$(basename "${OUT}")" >"$(basename "${OUT}").sha256" )

# ---------------------------------------------------------------- 9. 报告
SIZE_H="$(du -h "${OUT}" | cut -f1)"
SIZE_B="$(stat -c %s "${OUT}" 2>/dev/null || echo 0)"
APP_TGZ_SIZE="$(tar xzf "${OUT}" -O app.tgz 2>/dev/null | wc -c)"

echo
c_ok "打包完成"
echo "  产物   : ${OUT}"
echo "  大小   : ${SIZE_H} (${SIZE_B} bytes)"
echo "  sha256 : $(cut -d' ' -f1 "${OUT}.sha256")"
echo "  fnpack : ${FNPCK_VER}"
echo "  版本   : ${VERSION}   平台: ${PLATFORM}   二进制: ${BIN_LIST}"
if [ "${APP_TGZ_SIZE}" -lt 10240 ]; then
    c_warn "app.tgz 只有 ${APP_TGZ_SIZE} 字节，安装后 target/ 可能不完整"
fi
echo
echo "  包内清单："
tar tzvf "${OUT}" | sed 's/^/    /'
echo
echo "  下一步："
echo "    scp ${OUT} nas:/tmp/ && ssh nas 'appcenter-cli install-fpk /tmp/$(basename "${OUT}")'"
echo "    或：应用中心 → 手动安装 → 选择该 .fpk"
