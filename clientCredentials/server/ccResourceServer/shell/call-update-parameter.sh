#!/usr/bin/env bash
#
# 呼叫 resourceServer 的 UpdateParameter API。
# 流程：向 oauthserver 取得 Client Credentials JWT → 帶 Bearer Token 呼叫 UpdateParameter。
#
# 前置條件：
#   • oauthserver 已啟動於 http://localhost:9000
#   • resourceServer 已啟動於 http://localhost:8080
#   • 已安裝 curl 與 jq
#
# 用法：
#   ./call-update-parameter.sh                       # 使用預設 admin-client / admin
#   CLIENT_ID=member-client CLIENT_SECRET=member-secret SCOPE=member ./call-update-parameter.sh
#
set -euo pipefail

AUTH_SERVER="${AUTH_SERVER:-http://localhost:9000}"
RESOURCE_SERVER="${RESOURCE_SERVER:-http://localhost:8081}"
CLIENT_ID="${CLIENT_ID:-admin-client}"
CLIENT_SECRET="${CLIENT_SECRET:-admin-secret}"
SCOPE="${SCOPE:-admin}"

# 依賴檢查
for cmd in curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "錯誤：缺少必要工具 $cmd" >&2
    exit 1
  fi
done

echo "==> 1. 取得 access token from ${AUTH_SERVER}"
TOKEN_RESPONSE=$(curl -sS -u "${CLIENT_ID}:${CLIENT_SECRET}" \
  -d "grant_type=client_credentials&scope=${SCOPE}" \
  "${AUTH_SERVER}/oauth2/token")

echo "Token endpoint 回應："
echo "${TOKEN_RESPONSE}" | jq

ACCESS_TOKEN=$(echo "${TOKEN_RESPONSE}" | jq -r '.access_token // empty')
if [[ -z "${ACCESS_TOKEN}" ]]; then
  echo "錯誤：取得 token 失敗" >&2
  exit 1
fi

echo "取得的Token: ${ACCESS_TOKEN}"

echo
echo "==> 2. 解析 JWT payload"
PAYLOAD=$(echo "${ACCESS_TOKEN}" | cut -d. -f2)
# base64url → base64 補 padding
PAD=$(( (4 - ${#PAYLOAD} % 4) % 4 ))
PADDED=$(printf '%s%*s' "${PAYLOAD}" "${PAD}" '' | tr ' ' '=' | tr '_-' '/+')
echo "${PADDED}" | base64 -d 2>/dev/null | jq

echo
echo "正確的內容，應回應 0"
echo "==> 3. 呼叫 ${RESOURCE_SERVER}/sEQI/Param/UpdateParameter"
REQUEST_BODY='{
  "Check_Valid_Only": "Y",
  "Request": [
    { "Param_Type": "01", "Version": "100" }
  ]
}'
echo "Request body:"
echo "${REQUEST_BODY}" | jq

echo
RESPONSE=$(curl -sS -w "\n__HTTP__%{http_code}" \
  -X POST \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json; charset=utf-8" \
  -d "${REQUEST_BODY}" \
  "${RESOURCE_SERVER}/sEQI/Param/UpdateParameter")

HTTP_CODE=$(echo "${RESPONSE}" | sed -n 's/.*__HTTP__//p')
BODY=$(echo "${RESPONSE}" | sed '$d')

echo "HTTP ${HTTP_CODE}"
echo "Response body:"
echo "${BODY}" | jq


echo
echo "錯誤的內容，應回應 -1"
echo "==> 3. 呼叫 ${RESOURCE_SERVER}/sEQI/Param/UpdateParameter"
REQUEST_BODY='{
  "Check_Valid_Only": "Y",
  "Request": [
    { "Param_Type": "00", "Version": "100" }
  ]
}'
echo "Request body:"
echo "${REQUEST_BODY}" | jq

echo
RESPONSE=$(curl -sS -w "\n__HTTP__%{http_code}" \
  -X POST \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json; charset=utf-8" \
  -d "${REQUEST_BODY}" \
  "${RESOURCE_SERVER}/sEQI/Param/UpdateParameter")

HTTP_CODE=$(echo "${RESPONSE}" | sed -n 's/.*__HTTP__//p')
BODY=$(echo "${RESPONSE}" | sed '$d')

echo "HTTP ${HTTP_CODE}"
echo "Response body:"
echo "${BODY}" | jq