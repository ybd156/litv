#!/usr/bin/env bash
# =============================================================================
# 推送前自检
#
# 为什么要有这个：这个仓库的 CI 一轮要 10 分钟以上（native 构建更久），
# 而最近的失败里有好几次是**本地就能查出来的低级错误**——
# YAML 语法错、shell 续行被注释截断、工作流表达式写错。
# 拿 CI 当语法检查器太贵了，推之前先跑这个。
#
# 检查内容：
#   1. 所有 .github/workflows/*.yml 能被 YAML 解析
#   2. 所有 .sh 脚本 bash -n 通过
#   3. 所有 .py 脚本语法正确
#   4. shell 脚本里没有「注释插进续行命令」
#
# 用法：./tools/checks/preflight.sh
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

# Windows 的 Git Bash 里通常只有 python，没有 python3
PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done

fail=0
say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
bad() { printf '\033[31m[FAIL]\033[0m %s\n' "$*"; fail=1; }

# ---------------------------------------------------------------- 1. YAML
say "检查工作流 YAML"
if [ -z "$PY" ]; then
  echo "  （没有 python，跳过 YAML 检查）"
elif ! "$PY" -c "import yaml" >/dev/null 2>&1; then
  # 「有 python 但没装 PyYAML」必须和「YAML 真写坏了」区分开。
  # 以前这里不区分：缺依赖时每个工作流都报「解析失败」，
  # 于是「装个包就好」和「门禁文件真的坏了」在日志里长得一模一样。
  # CI 里由 LITV_STRICT_YAML=1 强制为失败（build-check 会先装 PyYAML），
  # 本地则只提示，不让人为了跑一次预检先装一堆东西。
  if [ "${LITV_STRICT_YAML:-0}" = "1" ]; then
    bad "缺少 PyYAML，无法解析工作流 YAML（CI 里必须装：python3 -m pip install pyyaml==6.0.2）"
  else
    echo "  （缺少 PyYAML，跳过工作流 YAML 检查；CI 会强制检查。本地装：pip install pyyaml）"
  fi
else
  for f in .github/workflows/*.yml; do
    [ -f "$f" ] || continue
    if "$PY" -c "import yaml,sys; yaml.safe_load(open(sys.argv[1],encoding='utf-8'))" "$f" 2>/dev/null; then
      ok "$f"
    else
      bad "$f YAML 解析失败"
      "$PY" -c "import yaml,sys; yaml.safe_load(open(sys.argv[1],encoding='utf-8'))" "$f" 2>&1 | tail -3
    fi
  done
fi

# ---------------------------------------------------------------- 2. bash -n
say "检查 shell 脚本语法"
while IFS= read -r f; do
  if bash -n "$f" 2>/dev/null; then
    ok "$f"
  else
    bad "$f bash -n 失败"
    bash -n "$f" 2>&1 | head -3
  fi
done < <(find . -name '*.sh' -not -path './.git/*' -not -path '*/node_modules/*' -not -path '*/build/*' 2>/dev/null)

# ---------------------------------------------------------------- 3. python 语法
say "检查 python 脚本语法"
while IFS= read -r f; do
  if "$PY" -m py_compile "$f" 2>/dev/null; then
    ok "$f"
  else
    bad "$f 语法错误"
    "$PY" -m py_compile "$f" 2>&1 | tail -3
  fi
done < <(find tools -name '*.py' -not -path '*/.git/*' 2>/dev/null)

# ---------------------------------------------------------------- 4. 续行注释
say "检查续行命令里有没有插注释"
sh_files=$(find . -name '*.sh' -not -path './.git/*' -not -path '*/node_modules/*' -not -path '*/build/*' 2>/dev/null | tr '\n' ' ')
if [ -n "$sh_files" ]; then
  # shellcheck disable=SC2086
  if "$PY" tools/checks/check-continuation.py $sh_files; then
    ok "续行检查通过"
  else
    bad "有注释插在续行命令里"
  fi
fi

# ---------------------------------------------------------------- 结果
echo
if [ "$fail" -ne 0 ]; then
  printf '\033[31m预检失败，先修再推。\033[0m\n'
  exit 1
fi
printf '\033[32m预检全部通过，可以推送。\033[0m\n'
