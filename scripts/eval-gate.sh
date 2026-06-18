#!/usr/bin/env bash
#
# CI 评测门禁：跑黄金集 → 对照提交的基线（resources/eval/baseline[-set].json）→ 有回归则非零退出。
#
# 用法:
#   scripts/eval-gate.sh [set] [runs]
#     set   黄金集名: default(默认) | sql | a2a | workflow
#     runs  每个 case 跑几次(默认 3，越多越能压 temp=0.7 抖动)
#
# 环境变量:
#   EVAL_BASE_URL   应用地址 (默认 http://localhost:8080)
#   EVAL_API_KEY    带 SCOPE_eval 的 X-Api-Key (app.security.enabled=true 时必填)
#
# 前置: 应用已起；非 default 集需先开对应 profile (app.nl2sql/a2a/workflow.enabled) + 依赖(MySQL 等)。
#
# 退出码: 0 = 无回归; 1 = 有回归(门禁失败); 2 = 调用/环境错误。
set -euo pipefail

SET="${1:-default}"
RUNS="${2:-3}"
BASE_URL="${EVAL_BASE_URL:-http://localhost:8080}"
API_KEY="${EVAL_API_KEY:-}"

hdr=(-H "Content-Type: application/json")
[[ -n "$API_KEY" ]] && hdr+=(-H "X-Api-Key: $API_KEY")

url="$BASE_URL/eval/gate?set=$SET&runs=$RUNS"
echo ">> POST $url"

# -w 拿 HTTP code，-o 拿 body；不加 -f 以便回归(422)也能读 body
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT
code="$(curl -s -o "$body_file" -w '%{http_code}' -X POST "${hdr[@]}" "$url" || echo 000)"

if [[ "$code" == "000" ]]; then
  echo "!! 无法连接 $BASE_URL —— 应用起了吗？" >&2
  exit 2
fi

# 优先用 jq 美化 + 取字段；没有 jq 就直接打 body
if command -v jq >/dev/null 2>&1; then
  passed="$(jq -r '.passed' < "$body_file" 2>/dev/null || echo null)"
  echo "passed: $passed"
  jq -r '.regressions[]? | "  - " + .' < "$body_file" 2>/dev/null || true
  jq '{overallPassRate: .summary.overallPassRate, averageScore: .summary.averageScore, totalRuns: .summary.totalRuns}' < "$body_file" 2>/dev/null || true
else
  cat "$body_file"; echo
fi

case "$code" in
  200) echo ">> GATE PASS"; exit 0 ;;
  422) echo ">> GATE FAIL (回归，见上方 regressions)" >&2; exit 1 ;;
  *)   echo ">> 意外 HTTP $code（鉴权？set 不存在？profile 没开？）" >&2; exit 2 ;;
esac
