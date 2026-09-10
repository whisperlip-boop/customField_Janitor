#!/usr/bin/env python3
"""기획서 12장 테스트 케이스용 픽스처를 만든다.

목적별로 필드 A~G를 만들고 각 상태가 정확히 판정되는지 확인할 수 있게 한다.
docker Jira 8.13 전용이고, 지우고 다시 돌려도 되게 이름으로 기존 것을 재사용한다.

  JIRA_PASS='...' python3 tools/make-testdata.py

워크플로 참조(픽스처 E)는 REST 경로가 없어서 여기서 만들지 않는다.
tools/make-workflow-fixture.sh 가 DB를 직접 고치고 Jira 재시작을 요구한다.
"""
import json
import os
import sys
import urllib.parse
import urllib.error
import urllib.request
import uuid

# 주소·계정은 환경마다 다르므로 저장소에 적지 않는다. 환경변수로 넘긴다.
BASE = os.environ.get("JIRA_BASE") or sys.exit("JIRA_BASE 환경변수에 Jira 주소를 넣어야 한다")
USER = os.environ.get("JIRA_USER", "admin")
PASS = os.environ.get("JIRA_PASS")
if not PASS:
    print("JIRA_PASS 환경변수가 필요하다", file=sys.stderr)
    sys.exit(1)

TEXT_TYPE = "com.atlassian.jira.plugin.system.customfieldtypes:textfield"
TEXT_SEARCHER = "com.atlassian.jira.plugin.system.customfieldtypes:textsearcher"
USERPICKER_TYPE = "com.atlassian.jira.plugin.system.customfieldtypes:userpicker"
USERPICKER_SEARCHER = "com.atlassian.jira.plugin.system.customfieldtypes:userpickergroupsearcher"
# 멀티 사용자 선택은 이슈 1건에 customfieldvalue 행을 여러 개 만든다.
# 기획서 함정 6("이슈 수"와 "값 행 수"를 혼동하지 말 것)을 실제로 재현한다.
MULTIUSER_TYPE = "com.atlassian.jira.plugin.system.customfieldtypes:multiuserpicker"
MULTIUSER_SEARCHER = "com.atlassian.jira.plugin.system.customfieldtypes:userpickergroupsearcher"
# 라벨 타입은 값을 customfieldvalue가 아니라 label 테이블에 저장한다(실측).
# DAO가 그 테이블까지 보는지 확인하는 픽스처다.
LABELS_TYPE = "com.atlassian.jira.plugin.system.customfieldtypes:labels"
LABELS_SEARCHER = "com.atlassian.jira.plugin.system.customfieldtypes:labelsearcher"

# 픽스처를 얹을 프로젝트. 없으면 만든다.
# 화면 ID는 하드코딩하지 않고 프로젝트 생성 후 이름으로 찾는다 — 인스턴스마다 다르고,
# 새로 띄운 atlas-run 인스턴스에는 아예 없다(실측: 8.17.1 신규 인스턴스는 프로젝트 0개,
# 화면은 Default/Resolve/Workflow 3개뿐이었다).
PROJECT_KEY = os.environ.get("JIRA_PROJECT", "TOP")
PROJECT_NAME = os.environ.get("JIRA_PROJECT_NAME", "top")
# 8.13.0과 8.17.1에서 키가 동일함을 실측했다.
PROJECT_TEMPLATE = "com.atlassian.jira-core-project-templates:jira-core-project-management"


def call(method, path, body=None, raw=False):
    url = BASE + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    request.add_header("X-Atlassian-Token", "no-check")
    import base64
    token = base64.b64encode(("%s:%s" % (USER, PASS)).encode()).decode()
    request.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read().decode("utf-8")
            return response.status, (text if raw else (json.loads(text) if text else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def call_form(method, path, params, user=None, password=None):
    """폼 인코딩 호출. 컬럼 설정 REST 는 JSON 이 아니라 columns= 를 여러 번 받는다.

    user/password 를 주면 그 계정으로 부른다 — 개인 컬럼 설정은 본인만 바꿀 수 있다
    (실측: admin 이 ?username=... 로 불러도 200 이 나오지만 <b>자기 설정</b>이 바뀐다).
    """
    import base64
    body = "&".join("%s=%s" % (k, urllib.parse.quote(str(v))) for k, v in params)
    request = urllib.request.Request(BASE + path, data=body.encode("utf-8"), method=method)
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    request.add_header("X-Atlassian-Token", "no-check")
    token = base64.b64encode(("%s:%s" % (user or USER, password or PASS)).encode()).decode()
    request.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def existing_fields():
    """이름 -> 같은 이름 필드 목록(숫자 ID 오름차순).

    같은 이름이 여러 개 있는 것이 이 픽스처의 의도(픽스처 G)이므로 이름을 키로
    하나만 담으면 안 된다. 여러 번 돌려도 결과가 같아야 하니 정렬해서 첫 번째를
    쓴다 — 그러지 않으면 실행마다 다른 필드를 집어 픽스처가 흐트러진다.
    """
    code, fields = call("GET", "/rest/api/2/field")
    if code != 200:
        raise SystemExit("필드 목록 조회 실패: %s %s" % (code, fields))
    grouped = {}
    for field in fields:
        if field.get("custom"):
            grouped.setdefault(field["name"], []).append(field)
    for same_name in grouped.values():
        same_name.sort(key=lambda f: int(f["id"].replace("customfield_", "")))
    return grouped


def ensure_field(name, description, field_type=TEXT_TYPE, searcher=TEXT_SEARCHER, allow_duplicate=False):
    """이름으로 찾아 재사용한다. allow_duplicate면 같은 이름이 정확히 2개가 되게 한다(픽스처 G)."""
    found = existing_fields().get(name, [])
    if allow_duplicate:
        if len(found) >= 2:
            print("  재사용(중복 짝): %s (%s)" % (name, found[1]["id"]))
            return found[1]["id"]
    elif found:
        print("  재사용: %s (%s)" % (name, found[0]["id"]))
        return found[0]["id"]
    code, body = call("POST", "/rest/api/2/field", {
        "name": name, "description": description, "type": field_type, "searcherKey": searcher,
    })
    if code not in (200, 201):
        raise SystemExit("필드 생성 실패 %s: %s %s" % (name, code, body))
    print("  생성: %s (%s)" % (name, body["id"]))
    return body["id"]


_screen_cache = []


def target_screens():
    """픽스처를 얹을 화면 ID들.

    프로젝트 템플릿으로 프로젝트를 만들면 Jira가 "<KEY>: ... Create Issue Screen" /
    "... Edit/View Issue Screen" 이름의 전용 화면을 만든다. 그 이름으로 찾는다.
    편집 화면이 반드시 포함돼야 한다 — 없으면 REST(PUT /issue)로 값을 넣을 수 없다
    ("Field cannot be set. It is not on the appropriate screen, or unknown.").
    """
    if _screen_cache:
        return _screen_cache
    code, screens = call("GET", "/rest/api/2/screens")
    if code != 200:
        raise SystemExit("화면 목록 조회 실패: %s %s" % (code, screens))
    prefix = PROJECT_KEY + ":"
    picked = [s["id"] for s in screens
              if str(s.get("name", "")).startswith(prefix)
              and ("Create" in s["name"] or "Edit" in s["name"])]
    if not picked:
        # 전용 화면이 없는 프로젝트(기본 스킴 사용)면 Default Screen 하나로 충분하다.
        picked = [s["id"] for s in screens if s.get("name") == "Default Screen"]
    if not picked:
        raise SystemExit("얹을 화면을 찾지 못했다: %s" % [s.get("name") for s in screens])
    _screen_cache.extend(picked)
    print("  대상 화면: %s" % picked)
    return _screen_cache


def add_to_screens(field_id, screen_ids=None):
    for screen_id in (screen_ids or target_screens()):
        add_to_screen(field_id, screen_id)


def remove_from_screens(field_id, screen_ids=None):
    for screen_id in (screen_ids or target_screens()):
        remove_from_screen(field_id, screen_id)


def ensure_project():
    """픽스처용 프로젝트를 찾거나 만든다. 새로 띄운 인스턴스에는 프로젝트가 없다."""
    code, projects = call("GET", "/rest/api/2/project")
    if code == 200:
        for project in projects:
            if project.get("key") == PROJECT_KEY:
                print("  재사용: 프로젝트 %s (%s)" % (PROJECT_KEY, project["id"]))
                return
    code, me = call("GET", "/rest/api/2/myself")
    lead = me.get("name") if code == 200 else USER
    code, body = call("POST", "/rest/api/2/project", {
        "key": PROJECT_KEY,
        "name": PROJECT_NAME,
        "projectTypeKey": "business",
        "projectTemplateKey": PROJECT_TEMPLATE,
        "lead": lead,
    })
    if code not in (200, 201):
        raise SystemExit("프로젝트 생성 실패: %s %s" % (code, body))
    print("  생성: 프로젝트 %s (%s)" % (PROJECT_KEY, body.get("id")))


def add_to_screen(field_id, screen_id):
    code, tabs = call("GET", "/rest/api/2/screens/%d/tabs" % screen_id)
    if code != 200:
        raise SystemExit("탭 조회 실패: %s %s" % (code, tabs))
    tab_id = tabs[0]["id"]
    code, body = call("POST", "/rest/api/2/screens/%d/tabs/%d/fields" % (screen_id, tab_id),
                      {"fieldId": field_id})
    print("  화면 추가 %s -> screen %d: %s" % (field_id, screen_id, code))


def remove_from_screen(field_id, screen_id):
    code, tabs = call("GET", "/rest/api/2/screens/%d/tabs" % screen_id)
    for tab in tabs:
        code, _ = call("DELETE", "/rest/api/2/screens/%d/tabs/%d/fields/%s"
                       % (screen_id, tab["id"], field_id))
        print("  화면 제거 %s <- screen %d tab %d: %s" % (field_id, screen_id, tab["id"], code))


SECOND_USER = os.environ.get("JIRA_SECOND_USER", "janitor-tester")
# 픽스처 사용자의 비밀번호. 소스에 적어두면 공개 저장소에 그대로 실린다.
# 없으면 매번 새로 만든다 — 이 사용자는 값 2개를 넣는 데만 쓰고 로그인하지 않는다.
SECOND_PASS = os.environ.get("JIRA_SECOND_PASS") or ("fx-" + uuid.uuid4().hex[:16])


def ensure_second_user():
    """멀티 사용자 선택 픽스처(H)에는 사용자가 둘 필요하다.

    새로 띄운 인스턴스에는 admin 하나뿐이라 없으면 만든다(실측: 8.17.1 신규
    인스턴스에서 "Could not find usernames: janitor-tester" 로 실패했다).
    """
    code, _ = call("GET", "/rest/api/2/user?username=%s" % SECOND_USER)
    if code == 200:
        print("  재사용: 사용자 %s" % SECOND_USER)
        return SECOND_USER
    code, body = call("POST", "/rest/api/2/user", {
        "name": SECOND_USER,
        "password": SECOND_PASS,
        "emailAddress": "%s@example.com" % SECOND_USER,
        "displayName": "Janitor Tester",
    })
    if code in (200, 201):
        print("  생성: 사용자 %s" % SECOND_USER)
        return SECOND_USER
    print("  사용자 생성 실패(%s) — 픽스처 H는 값 1개로 진행한다: %s" % (code, body))
    return None


def ensure_issues(count, project_key=None):
    project_key = project_key or PROJECT_KEY
    code, result = call("GET", "/rest/api/2/search?jql=project=%s+order+by+key&maxResults=50&fields=summary"
                        % project_key)
    keys = [i["key"] for i in result.get("issues", [])]
    code, meta = call("GET", "/rest/api/2/project/%s" % project_key)
    issue_type = meta["issueTypes"][0]["id"]
    while len(keys) < count:
        code, body = call("POST", "/rest/api/2/issue", {"fields": {
            "project": {"key": project_key},
            "summary": "janitor fixture issue %d" % (len(keys) + 1),
            "issuetype": {"id": issue_type},
        }})
        if code not in (200, 201):
            raise SystemExit("이슈 생성 실패: %s %s" % (code, body))
        keys.append(body["key"])
        print("  이슈 생성: %s" % body["key"])
    return keys[:count]


def set_value(issue_key, field_id, value):
    code, body = call("PUT", "/rest/api/2/issue/%s" % issue_key, {"fields": {field_id: value}})
    print("  값 입력 %s.%s: %s %s" % (issue_key, field_id, code, body if code >= 400 else ""))


def main():
    print("프로젝트 준비")
    ensure_project()

    print("필드 A (활성 기대): 화면에 걸고 값 입력")
    a = ensure_field("Janitor Fixture A", "expected: active")
    add_to_screens(a)

    print("필드 B (방치 기대): 화면에만 걸고 값 없음")
    b = ensure_field("Janitor Fixture B", "expected: neglected")
    add_to_screens(b)

    print("필드 C (유령 데이터 기대): 값 입력 후 화면에서 제거")
    c = ensure_field("Janitor Fixture C", "expected: orphan data")
    add_to_screens(c)

    print("필드 D (미사용 기대): 아무 곳에도 안 씀")
    ensure_field("Janitor Fixture D", "expected: unused")

    print("필드 E (위험 기대): 워크플로 후처리 함수에서 참조 - tools/make-workflow-fixture.sh 필요")
    e = ensure_field("Janitor Fixture E", "expected: at risk via workflow")

    print("필드 F (위험 기대): 권한 스킴 userCF 참조")
    f = ensure_field("Janitor Fixture F", "expected: at risk via permission scheme",
                     USERPICKER_TYPE, USERPICKER_SEARCHER)

    print("필드 G (A와 동명 - 중복 표시 기대)")
    ensure_field("Janitor Fixture A", "expected: duplicate name warning", allow_duplicate=True)

    print("필드 H (이슈 수 != 값 행 수 확인): 멀티 사용자 선택, 이슈 1건에 사용자 2명")
    h = ensure_field("Janitor Fixture H multi-value", "expected: 1 issue, 2 value rows",
                     MULTIUSER_TYPE, MULTIUSER_SEARCHER)
    add_to_screens(h)

    print("필드 I (label 테이블 저장 확인): 라벨 타입, 이슈 1건에 라벨 3개")
    i = ensure_field("Janitor Fixture I labels", "expected: 1 issue, 3 label rows",
                     LABELS_TYPE, LABELS_SEARCHER)
    add_to_screens(i)

    print("두 번째 사용자 준비 (픽스처 H용)")
    second = ensure_second_user()

    print("이슈 준비 및 값 입력")
    issues = ensure_issues(3)
    for key in issues:
        set_value(key, a, "A value")
    set_value(issues[0], c, "C value")
    set_value(issues[1], c, "C value")
    holders = [{"name": USER}] + ([{"name": second}] if second else [])
    set_value(issues[0], h, holders)
    set_value(issues[0], i, ["alpha", "beta", "gamma"])

    print("필드 C를 화면에서 제거 (값은 남는다)")
    remove_from_screens(c)

    # 권한 스킴 항목을 세 권한에 넣는다.
    #
    # 한 권한만 넣으면 v1.0.1 의 회귀를 못 잡는다. 스킴 참조의 동일성 키에 권한
    # 구분이 없으면 세 항목이 하나로 합쳐지는데, 항목이 하나뿐이면 합칠 것도 없어서
    # 픽스처가 통과한다. 실제로 그렇게 놓쳤다.
    #
    # 기대: 이 필드의 PERMISSION_SCHEME 참조가 3건, riskyReferences 에 3이 포함된다.
    print("권한 스킴에 userCF 항목 추가 (필드 F) — 세 권한")
    code, schemes = call("GET", "/rest/api/2/permissionscheme")
    scheme_id = schemes["permissionSchemes"][0]["id"]
    # parameter 형식이 인스턴스에 따라 다를 수 있어 통하는 쪽을 먼저 찾는다.
    working_parameter = None
    for parameter in (f, f.replace("customfield_", "")):
        code, body = call("POST", "/rest/api/2/permissionscheme/%d/permission" % scheme_id, {
            "holder": {"type": "userCustomField", "parameter": parameter},
            "permission": "BROWSE_PROJECTS",
        })
        print("  BROWSE_PROJECTS parameter=%s -> %s %s" % (parameter, code, body if code >= 400 else ""))
        # "already exists" 는 실패가 아니다 — 이미 넣혀 있다는 뜻이므로 이 형식이
        # 통하는 형식이다. 재실행 가능해야 하니 성공으로 취급한다.
        if code in (200, 201) or "already exists" in str(body):
            working_parameter = parameter
            break
    if working_parameter is not None:
        for permission in ("EDIT_ISSUES", "ASSIGNABLE_USER"):
            code, body = call("POST", "/rest/api/2/permissionscheme/%d/permission" % scheme_id, {
                "holder": {"type": "userCustomField", "parameter": working_parameter},
                "permission": permission,
            })
            ok = code in (200, 201) or "already exists" in str(body)
            print("  %s -> %s%s" % (permission, "OK" if ok else code,
                                    "" if ok else " " + str(body)))
    else:
        print("  통하는 parameter 형식을 못 찾았다 — 픽스처 F 는 알림 스킴만으로 진행한다")

    print("\n필터 준비 (이름 참조 / cf[ID] 참조)")
    filter_ids = []
    for name, jql in (
            ("Janitor fixture by name", '"Janitor Fixture A" is not EMPTY'),
            ("Janitor fixture by id", "cf[%s] is not EMPTY" % a.replace("customfield_", "")),
    ):
        code, body = call("POST", "/rest/api/2/filter", {"name": name, "jql": jql, "favourite": False})
        print("  필터 %s -> %s %s" % (name, code, body if code >= 400 else ""))
        if code in (200, 201) and isinstance(body, dict):
            filter_ids.append(body["id"])

    # 이슈 네비게이터 컬럼 설정 (픽스처 J). 세 종류를 전부 만든다 — 시스템 기본은
    # Jira 가 이미 갖고 있고(Development), 여기서는 필터 컬럼 2개와 개인 컬럼 2명을
    # 만든다.
    #
    # 둘씩 만드는 이유는 픽스처 F 와 같다: 대상이 하나뿐이면 중복 제거가 참조를
    # 뭉개도 픽스처가 통과한다. 개인 컬럼은 필드마다 한 건으로 합치는 것이 의도이므로
    # 사람 수(2)가 부가 정보에 나와야 한다.
    #
    # 기대: 필드 A 의 COLUMN_LAYOUT 참조 = 필터 2 + 개인 1(2명) = 3건.
    print("\n이슈 네비게이터 컬럼 설정 (픽스처 J)")
    for filter_id in filter_ids:
        code, body = call_form("PUT", "/rest/api/2/filter/%s/columns" % filter_id,
                               [("columns", "issuekey"), ("columns", a)])
        print("  필터 %s 의 컬럼 -> %s%s" % (filter_id, code, "" if code < 400 else " " + body[:120]))
    if not filter_ids:
        print("  필터가 이미 있어 ID 를 못 받았다 — 컬럼은 앞선 실행에서 이미 설정되어 있다")

    code, body = call_form("PUT", "/rest/api/2/user/columns",
                           [("columns", "issuekey"), ("columns", a)])
    print("  %s 의 개인 컬럼 -> %s%s" % (USER, code, "" if code < 400 else " " + body[:120]))
    if second:
        code, body = call_form("PUT", "/rest/api/2/user/columns",
                               [("columns", "issuekey"), ("columns", a)],
                               user=second, password=SECOND_PASS)
        if code < 400:
            print("  %s 의 개인 컬럼 -> %s" % (second, code))
        else:
            # 계정이 이미 있고 비밀번호를 모르면 여기서 401 이 난다. 실패가 아니다 —
            # 개인 컬럼이 한 명뿐이 되어 "2명" 대신 "1명"으로 나올 뿐이다.
            print("  %s 의 개인 컬럼 -> %s (JIRA_SECOND_PASS 를 주면 두 명이 된다)"
                  % (second, code))

    # 워크플로 픽스처(E)는 XML 안에 필드 ID가 박혀야 한다. 인스턴스마다 다르므로
    # 여기서 실제 ID를 파일로 남겨 make-workflow-fixture.sh 가 치환에 쓰게 한다.
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fixture-e-field-id"), "w") as fh:
        fh.write(e.replace("customfield_", ""))
    print("\n픽스처 E 필드 ID: %s (tools/.fixture-e-field-id 에 기록)" % e)

    print("\n완료. 이제 /secure/admin/CustomFieldUsage.jspa 에서 스캔하고 상태를 확인할 것.")
    print("필드 E의 워크플로 참조는 tools/make-workflow-fixture.sh 로 따로 만든다.")


if __name__ == "__main__":
    main()
