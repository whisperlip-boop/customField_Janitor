#!/usr/bin/env bash
# 버전에 따라 달라질 수 있는 사실을 아무 Jira 인스턴스에서 뽑아낸다.
#
# 이 플러그인은 8.13으로 컴파일해 8.17.1에 올린다. 그 가정이 성립하는지 확인하려면
# 두 버전에서 같은 항목을 뽑아 대조해야 한다. 특히 web-item의 section 키는 한 번
# 기획서 기대값과 실측이 달랐던 항목이다(docs/00-환경실측.md [확인 4]).
#
#   ./tools/probe-jira.sh http://host:port            <admin> '<암호>'
#   ./tools/probe-jira.sh http://localhost:2990/jira  admin admin   # atlas-run 기본값
#
set -uo pipefail

BASE="${1:?사용법: probe-jira.sh <base-url> <user> <pass>}"
USER="${2:?}"
PASS="${3:?}"
JAR="$(mktemp -d)/cookies.txt"

say() { printf '\n== %s\n' "$1"; }
q()   { curl -s -u "$USER:$PASS" -m 60 "$@"; }

# 로그인 + 웹수도 (관리 화면 조회용)
curl -s -c "$JAR" -H 'Content-Type: application/json' -X POST \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" "$BASE/rest/auth/1/session" -o /dev/null
curl -s -b "$JAR" -c "$JAR" -X POST \
    --data-urlencode "webSudoPassword=$PASS" \
    --data-urlencode "webSudoDestination=/secure/admin/ViewCustomFields.jspa" \
    --data-urlencode "webSudoIsPost=false" \
    "$BASE/secure/admin/WebSudoAuthenticate.jspa" -o /dev/null

say "1. 서버 버전"
q "$BASE/rest/api/2/serverInfo" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('  version   :', d.get('version'))
print('  buildNumber:', d.get('buildNumber'))
print('  deployment:', d.get('deploymentType'))
" 2>/dev/null || echo "  조회 실패"

say "2. 커스텀 필드 관리 화면 (서버 렌더링 여부 / 행 후크)"
curl -s -b "$JAR" -m 60 "$BASE/secure/admin/ViewCustomFields.jspa" -o /tmp/probe-vcf.html
python3 - <<'PY'
import re
h = open('/tmp/probe-vcf.html', encoding='utf-8', errors='replace').read()
title = re.search(r'<title>([^<]*)', h)
print('  title                :', title.group(1).strip() if title else '?')
print('  #customfields-container:', 'customfields-container' in h)
print('  서버 렌더링 <th> 개수 :', len(re.findall(r'<th\b', h)))
print('  data-custom-field-id  :', h.count('data-custom-field-id'), '(JS 렌더링이면 0이 정상)')
m = re.search(r'name="admin\.active\.section"\s+content="([^"]*)"', h)
print('  admin.active.section  :', m.group(1) if m else '(페이지에 없음)')
m = re.search(r'name="admin\.active\.tab"\s+content="([^"]*)"', h)
print('  admin.active.tab      :', m.group(1) if m else '(페이지에 없음)')
i = h.find('aui-nav-heading">Fields')
if i < 0: i = h.find('aui-nav-heading">')
seg = h[max(0,i-40):i+700]
print('  Fields 그룹 링크      :', re.findall(r'id="([a-z_0-9-]+)"\s*>([^<]{0,40})', seg)[:8])
PY

say "3. 우리 web-item 이 사이드바에 붙었나"
grep -o 'id="cf-usage-link"[^>]*>[^<]*' /tmp/probe-vcf.html || echo "  (안 붙음)"

say "4. Jira가 커스텀 필드로 인식하는 개수"
q "$BASE/rest/api/2/customFields?maxResults=100" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('  total:', d.get('total'))
locked=[v['name'] for v in d.get('values',[]) if v.get('isLocked')]
print('  isLocked:', locked)
" 2>/dev/null || echo "  조회 실패 (이 버전에 엔드포인트가 없을 수 있다)"

say "5. Jira Software 애플리케이션 / 프로젝트 템플릿"
q "$BASE/rest/api/2/applicationrole" | python3 -c "
import json,sys
for r in json.load(sys.stdin): print('  ', r.get('key'), '| defined:', r.get('defined'), '| seats:', r.get('numberOfSeats'))
" 2>/dev/null || echo "  조회 실패"
q "$BASE/rest/project-templates/1.0/templates" | python3 -c "
import json,sys
d=json.load(sys.stdin); found=[]
def walk(o):
    if isinstance(o,dict):
        if isinstance(o.get('name'),str): found.append(o['name'])
        for v in o.values(): walk(v)
    elif isinstance(o,list):
        for v in o: walk(v)
walk(d); print('  템플릿:', sorted(set(found)))
" 2>/dev/null || echo "  템플릿 조회 실패"

say "6. 우리 플러그인 상태"
q "$BASE/rest/plugins/1.0/com.bskim.jira.janitor-key" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('  enabled:', d.get('enabled'), '| version:', d.get('version'))
for m in d.get('modules',[]): print('   -', m['key'], m['enabled'])
" 2>/dev/null || echo "  플러그인 미설치"

say "7. 스캔 실행 후 결과 요약"
q -H 'X-Atlassian-Token: no-check' -X POST "$BASE/rest/janitor/1.0/scan" -o /dev/null
for _ in $(seq 1 30); do
    sleep 2
    S="$(q "$BASE/rest/janitor/1.0/scan/status")"
    echo "$S" | grep -q '"state":"DONE"' && break
    echo "$S" | grep -q '"state":"FAILED"' && break
done
q "$BASE/rest/janitor/1.0/fields" | python3 -c "
import json,sys
from collections import Counter
d=json.load(sys.stdin)
print('  필드 수:', d['scan'].get('fieldCount'), '| 소요:', d['scan'].get('lastScanDurationMs'), 'ms')
print('  상태 분포:', dict(Counter(f['status'] for f in d['fields'])))
print('  잠긴 필드:', [f['name'] for f in d['fields'] if f['locked']])
print('  타입 없음:', [f['name'] for f in d['fields'] if not f['typeAvailable']])
print('  확인 불가:', [(p['area'], p['target']) for p in d['problems']])
" 2>/dev/null || echo "  스캔 결과 조회 실패"

say "8. 권한 (익명 호출)"
printf '  익명 /fields : '; curl -s -o /dev/null -m 30 -w '%{http_code}\n' "$BASE/rest/janitor/1.0/fields"

say "9. 웹수도 (로그인만 한 세션)"
J2="$(mktemp -d)/c.txt"
curl -s -c "$J2" -H 'Content-Type: application/json' -X POST \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" "$BASE/rest/auth/1/session" -o /dev/null
printf '  우리 화면 title: '
curl -s -b "$J2" -m 60 "$BASE/secure/admin/CustomFieldUsage.jspa" | grep -o '<title>[^<]*' | head -1
