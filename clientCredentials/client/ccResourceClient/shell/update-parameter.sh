#!/usr/bin/env bash
#
# 呼叫 resourceClient 的 /client/UpdateParameter 端點以進行測試。
# resourceClient 會自動向 oauthserver 取得 token，再轉打 resourceServer
# 的 /sEQI/Param/UpdateParameter。
#
# 用法：
#   ./update-parameter.sh [paramType] [version]
#   PARAM_TYPE=01 VERSION=v1 BASE_URL=http://localhost:8080 ./update-parameter.sh
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PARAM_TYPE="${1:-${PARAM_TYPE:-01}}"
VERSION="${2:-${VERSION:-v1}}"

URL="${BASE_URL}/client/UpdateParameter?paramType=${PARAM_TYPE}&version=${VERSION}"

echo "POST ${URL}"
echo "---"

curl -sS -i -X POST \
  -H 'Accept: application/json' \
  "${URL}"
echo