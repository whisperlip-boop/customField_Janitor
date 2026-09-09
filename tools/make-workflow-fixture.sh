#!/usr/bin/env bash
# 픽스처 E: 워크플로 참조를 만든다.
#
# REST 경로가 없어서 Jira의 "Import Workflow From XML" 관리 화면을 그대로 쓴다.
# DB를 직접 고치지 않으므로 재시작이 필요 없고, Jira가 디스크립터를 검증해준다.
#
#   JIRA_BASE=http://host:port JIRA_USER=admin JIRA_PASS='...' ./tools/make-workflow-fixture.sh
set -euo pipefail

cd "$(dirname "$0")/.."

BASE="${JIRA_BASE:?JIRA_BASE 환경변수에 Jira 주소를 넣어야 한다}"
USER="${JIRA_USER:-admin}"
PASS="${JIRA_PASS:?JIRA_PASS 환경변수가 필요하다}"
NAME="${WORKFLOW_NAME:-Janitor Fixture Workflow}"
JAR=$(mktemp -d)/cookies.txt

# 로그인 + 웹수도. 관리 액션은 둘 다 필요하다.
curl -s -c "$JAR" -H 'Content-Type: application/json' -X POST \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
    "$BASE/rest/auth/1/session" -o /dev/null
curl -s -b "$JAR" -c "$JAR" -X POST \
    --data-urlencode "webSudoPassword=$PASS" \
    --data-urlencode "webSudoDestination=/secure/admin/workflows/ListWorkflows.jspa" \
    --data-urlencode "webSudoIsPost=false" \
    "$BASE/secure/admin/WebSudoAuthenticate.jspa" -o /dev/null

# 필드 ID는 인스턴스마다 다르다. make-testdata.py 가 남긴 값을 쓰고, 없으면 이름으로 찾는다.
FIELD_NUM="${FIELD_E_ID:-}"
if [ -z "$FIELD_NUM" ] && [ -f tools/.fixture-e-field-id ]; then
    FIELD_NUM="$(cat tools/.fixture-e-field-id)"
fi
if [ -z "$FIELD_NUM" ]; then
    FIELD_NUM="$(curl -s -u "$USER:$PASS" "$BASE/rest/api/2/field" \
        | python3 -c "import json,sys;print(next((f['id'].replace('customfield_','') for f in json.load(sys.stdin) if f.get('name')=='Janitor Fixture E'), ''))")"
fi
if [ -z "$FIELD_NUM" ]; then
    echo "픽스처 E 필드를 찾지 못했다. 먼저 make-testdata.py 를 돌릴 것." >&2
    exit 1
fi
echo "픽스처 E 필드 ID: customfield_$FIELD_NUM"

XML="$(mktemp)"
sed "s/@FIELD_E@/customfield_${FIELD_NUM}/g; s/@FIELD_E_NUM@/${FIELD_NUM}/g" \
    tools/janitor-fixture-workflow.xml > "$XML"

# XSRF 토큰은 폼에서 긁어온다. 폼 HTML이 여러 줄로 쪼개져 있어서 줄 단위 grep으로는
# 못 잡는다. 개행을 없애고 한 줄로 만든 뒤 찾는다.
FORM=$(mktemp)
curl -s -b "$JAR" "$BASE/secure/admin/workflows/ImportWorkflowFromXml!default.jspa" -o "$FORM"
TOKEN=$(tr '\n' ' ' < "$FORM" | grep -o 'name="atl_token"[^>]*value="[^"]*"' \
    | grep -o 'value="[^"]*"' | head -1 | sed 's/value="//;s/"$//' || true)

if [ -z "$TOKEN" ]; then
    echo "atl_token을 못 얻었다. 웹수도가 안 걸렸거나 폼이 바뀌었다: $FORM" >&2
    exit 1
fi

curl -s -b "$JAR" -X POST \
    --data-urlencode "name=$NAME" \
    --data-urlencode "description=Custom Field Janitor fixture (field E reference)" \
    --data-urlencode "definition=inline" \
    --data-urlencode "workflowXML@$XML" \
    --data-urlencode "atl_token=$TOKEN" \
    --data-urlencode "Import=Import" \
    "$BASE/secure/admin/workflows/ImportWorkflowFromXml.jspa" -o /tmp/janitor-wf-import.html \
    -w 'import=%{http_code}\n'

if grep -q "aui-message-error\|error\b" /tmp/janitor-wf-import.html; then
    echo "가져오기 응답에 오류 표시가 있다. /tmp/janitor-wf-import.html 을 확인할 것." >&2
fi
echo "워크플로 목록에서 '$NAME' 을 확인할 것: $BASE/secure/admin/workflows/ListWorkflows.jspa"
