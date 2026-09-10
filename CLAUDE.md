# Custom Field Janitor — 개발 메모

이 파일은 이 저장소에서 작업할 때 필요한 배경이다. 사용자용 설명은 [README.md](README.md),
환경 실측과 기획서와 달라진 판단은 [docs/00-환경실측.md](docs/00-환경실측.md)에 있다.

## 목표

"이 커스텀 필드를 지워도 되나?"에 답하는 재료를 모아 보여준다. **v1은 조회 전용이다.**
삭제 기능을 넣지 않는 것은 의도다 — 커스텀 필드 삭제는 되돌릴 수 없고, 관리자가
"이걸 누르면 뭔가 지워지나?"를 걱정하게 만들면 도구를 신뢰하지 않는다.
그래서 앱 이름과 메뉴 라벨에 "Cleanup"/"Delete"를 쓰지 않는다.

## 환경

- 빌드: Atlassian Plugin SDK (`atlas-mvn`), JDK 8
- 컴파일 대상: jira-api **8.13.0** (운영 타깃은 8.17.1)
- 테스트 인스턴스: docker Jira 8.13.0 + PostgreSQL (`schema-name=public`)
- 오프라인 빌드 가능(`atlas-mvn -o`). 단 최초 1회는 온라인이 필요하다.

**인스턴스 주소·계정은 저장소에 적지 않는다.** 아래 세 환경변수로 넘긴다.
스크립트도 전부 이 규약을 따른다.

```bash
export JIRA_BASE=http://<host>:<port>
export JIRA_USER=<admin>
export JIRA_PASS='...'
```

DB·로그를 직접 볼 때는 컨테이너 이름만 자기 환경 값으로 바꿔 쓴다:

```bash
docker exec <db-container> psql -U "$PGUSER" -d jira -c "SELECT COUNT(*) FROM customfield;"
docker exec <jira-container> tail -100 /var/atlassian/application-data/jira/log/atlassian-jira.log
```

## 구조

```
com.bskim.jira.janitor.fields
├── dao/          직접 SQL을 쓰는 유일한 곳 (JanitorDao)
├── model/        FieldUsage / Reference / UsageStatus / ScanResult
├── scan/         ScanService(싱글턴) + ScanContext + collector/*
├── rest/         FieldsResource + dto (public 필드 POJO → Jackson)
└── web/          CustomFieldUsageAction(webwork1) + AdminGuard + AdminOnlyCondition
```

새 참조 종류를 추가하려면: `ReferenceType`에 상수 하나, `ScanProgress.Stage`에 단계 하나,
`collector/`에 `ReferenceCollector` 구현 하나, `Collectors.ALL`에 한 줄, i18n 키
(`janitor.fields.ref.*`, `janitor.fields.stage.*`, `janitor.help.ref.{desc,impact}.*`),
그리고 **CSV 헤더(`FieldsResource`)와 `FieldSummaryDto`** — 이 둘만 종류를 이름으로
열거한다(실측 32번. 오래 "그 외는 손댈 곳이 없다"고 적혀 있었는데 사실이 아니었다).
설명서 표와 상세 화면의 그룹은 열거형을 순회하므로 자동이다.

대상 이름이 데이터에 없는 참조(예: 시스템 기본 컬럼)는 `targetName`을 비우고 종류를
`detailI18nKey`로 넘긴다. `detail.vm`이 그 키를 대상 자리에 그린다 — 스캔 스레드에는
보는 사람의 로케일이 없어서 이름을 그때 만들 수 없기 때문이다.

## 언어 전환 (`LocaleText`)

목록·상세·설명서 세 화면 모두 `?lang=ko` / `?lang=en` 으로 표시 언어를 고정할 수 있다.
파라미터가 없으면 보는 사람의 Jira 로케일을 따른다. 우측 상단 토글이 그 링크다.

- **세 화면 모두 `getText()` 대신 `text()` 만 쓴다.** 절반은 보는 사람 로케일,
  절반은 선택한 언어로 나오는 상태가 생기면 안 된다. 새 문구를 넣을 때 주의.
- **화면 간 링크는 `lang` 을 유지해야 한다.** 액션의 `detailUrl()` / `helpUrl()` /
  `selfUrl()` / `listUrl()` 을 쓰고 URL을 직접 적지 말 것.
- **진행률 폴링도 같은 언어로 답한다.** `/scan` 과 `/scan/status` 가 `lang` 쿼리
  파라미터를 받고 `JanitorI18n.text(key, lang)` 이 그 언어로 문구를 준다. 없으면
  호출자 로케일(기존 동작) — `inject.js` 는 넘기지 않으므로 보는 사람 로케일을 쓴다.
- 언어를 URL 파라미터로 둔 이유는 "한국어로 열리는 링크"를 사내에 그대로 공유할 수
  있게 하려는 것이다. JS로 두 언어를 렌더링하고 감추면 DOM이 두 배가 되고 공유가 안 된다.

## 표 안의 보조 표기 (`janitor-cellnote` / `janitor-cellwarn`)

표 셀의 `-`, `부정확`, `확인 불가` 는 **날짜·숫자와 같은 글자 크기**로 둔다.
`janitor-meta`(12px)를 쓰면 같은 컬럼에서 크기가 달라져 어긋나 보인다(실측 지적).
색만 다르게 두어 "값이 아니다"라는 신호를 남긴다 — `cellnote` 는 회색(없음),
`cellwarn` 은 호박색(신뢰할 수 없음).

## 사용 설명서 (`help.vm`)

`/secure/admin/CustomFieldUsageHelp.jspa`. 내용은 전부 i18n 번들(`janitor.help.*`)에 있고
템플릿에는 구조만 있다. 두 가지 규칙이 있다:

1. **상태 라벨 표와 참조 카테고리 표는 열거형을 순회해 만든다.** `UsageStatus` /
   `ReferenceType` 에 값을 추가하면 설명서에 행이 자동으로 생기고, i18n 키
   (`janitor.help.status.{cond,desc,act}.<code>`, `janitor.help.ref.{desc,impact}.<code>`)
   만 채우면 된다. 목차를 손으로 적어두면 본문을 고칠 때마다 어긋나는데,
   설명서와 실제 동작이 다른 것이 설명서가 없는 것보다 나쁘다.
2. **이 화면은 `getText()` 대신 `text()` 만 쓴다.** 언어를 `?lang=ko|en` 으로 고정하기
   때문에, 절반은 보는 사람 로케일 / 절반은 선택한 언어로 나오면 안 된다.
   언어를 URL 파라미터로 둔 이유는 "한국어로 열리는 링크"를 사내에 그대로 공유할 수
   있게 하려는 것이다.

우측 색인은 JS가 `.janitor-help-body` 의 `h2`/`h3` 에서 만든다(id 없으면 부여).
IntersectionObserver 로 현재 위치를 강조하고, 없으면 색인은 그대로 쓰이고 강조만 빠진다.

## 절대 바꾸지 말 것 (기획서의 근거)

1. **삭제 기능을 넣지 않는다.** 각 항목에서 Jira 표준 관리 화면으로 링크만 보낸다.
2. **모든 처리는 필드 ID 기준.** 이름으로 다루면 동명 필드를 잘못 지운다.
   UI에는 항상 ID를 함께 노출한다.
3. **참조 수집은 "전체 1회 순회 → 인덱스 → 캐시".** 필드마다 화면/워크플로를 반복
   순회하면 O(필드 × 설정)이 되어 필드 300개 인스턴스에서 쓸 수 없다.
4. **값 집계는 단일 GROUP BY.** 필드별 1회 조회는 수백 회 쿼리가 된다.
   JQL(`cf[x] is not EMPTY`) 방식은 검색 가능 설정과 인덱스 상태에 의존해서 안 쓴다.
5. **상태 라벨 5개를 늘리거나 줄이지 않는다.** 이 라벨이 앱의 정체성이다.
6. **plugin key `com.bskim.jira.janitor`는 절대 바꾸지 않는다.**
   표시 이름은 언제든 바꿀 수 있다. Java 패키지를 `...janitor.fields.*` 로 갈라 둔 것도
   두 번째 모듈에 대비한 것이다 — 다만 `users` 모듈은 합치지 않기로 했다(실측 38번).
   "패키지를 나눠 뒀으니 합칠 수 있다"가 합칠 이유는 아니다. 기준은
   **같은 사람이 같은 맥락에서 쓰는가** 다.
7. **실패를 조용히 누락시키지 않는다.** 등급이 둘이다.
   - **필수** — 값 집계(`customfieldvalue`, `label`)와 잠긴 필드(`managedconfigurationitem`).
     실패하면 `ScanFailedException`으로 **스캔 전체를 실패시킨다.** 부분 결과를 내지 않는다.
     이유: 이 재료가 빠지면 라벨이 비는 게 아니라 **거꾸로** 나온다. 값을 못 읽으면 0이
     남아 `judge(0,0,0,0)` = [미사용] = "삭제 안전"이고 정렬이 그 필드를 맨 위로 올린다.
     정보가 없는 것이 적극적인 삭제 신호로 바뀌는 것이 이 도구에서 가장 나쁜 실패다.
   - **보조** — 나머지 수집기, 마지막 변경일. "확인 불가" 목록으로 노출하고 계속한다.
     수가 줄어들 뿐 라벨이 뒤집히지 않는다.

   새 재료를 추가할 때 판단 기준은 두 단계다.
   - **"이게 실패하면 라벨이 뒤집히나?"** → `ReferenceCollector.isEssential()` 참.
   - **"이게 실패하면 판정 대상이 목록에서 사라지나?"** → 스캔은 계속하되 화면 맨 위
     배너로 알린다(`ScanResult.isFieldListDegraded()`). "확인 불가" 목록 한 줄로 두면
     표가 완전하다고 읽힌다.

   `isEssential`의 **반경은 좁게** 둔다. 필수인 것은 보통 DAO 조회 한 줄이다.
   표시용 이름 조회 같은 부수 작업이 같은 `collect()` 안에서 예외를 내면 스캔 전체가
   죽으므로, 그런 코드는 자체 `try`로 막는다(`ManagedFieldCollector.pluginName`).

   실패 사실은 `ScanService.getLastFailure()`에 따로 보관한다 — 진행률만 쓰면 화면을
   다시 그리는 순간 사라지고 이전 스캔의 표와 시각만 남아서, 관리자가 몇 주 전
   스냅샷을 방금 것으로 믿는다. 화면은 실패 배너를 서버측에서 그리고, 표가 낡았으면
   그 사실을 함께 알린다(`isResultStale()`).
8. **v1의 한계를 UI에 명시한다.** "참조 없음 = 삭제 안전"으로 읽히면 안 된다.
9. **클라이언트도 조용히 실패하지 않는다.** XHR이 401/403이면 폴링을 멈추고 버튼을
   되살리고 이유를 낸다. 이게 없으면 웹수도 만료 시 "눌렀는데 아무 일도 없고 버튼이
   죽었다"가 된다. 그래서 REST에는 `@WebSudoRequired`를 붙이지 않는다 — 스캔이
   웹수도 제한 시간보다 오래 걸리면 폴링이 스캔 도중 끊긴다(docs/00 29번).

## 함정 (직접 밟고 고친 것들)

전부 [docs/00-환경실측.md](docs/00-환경실측.md)에 근거와 함께 있다. 요약만:

- **web-item의 `section`은 `element_options_section/fields_section`이다.**
  `admin_issues_menu/` 접두사를 붙이면 메뉴가 조용히 사라진다(에러도 로그도 없다).
  화면 템플릿의 `admin.active.section` 메타는 반대로 3단 경로를 쓴다. 형식이 다르다.
- **`/secure/admin/` 경로만으로는 웹수도가 걸리지 않는다.** 액션에
  `@com.atlassian.sal.api.websudo.WebSudoRequired`가 있어야 한다(실측: 애노테이션 없이는
  로그인만 한 세션으로 화면이 그대로 열렸다). `sal-api` 4.2.0을 provided로 쓴다 —
  Jira 8.13이 번들하는 버전이다.
- **스캔 스레드에는 인증·i18n 컨텍스트가 없다.** `getIssueOperationName()` 같은 API가
  빈 값을 준다. 번역이 필요하면 `I18nHelper.BeanFactory.getInstance(Locale)`로 직접 한다.
- **`searchrequest.authorname`은 사용자 키(`JIRAUSER10000`)다.** `UserManager`로 풀어야 한다.
  `columnlayout.username`도 같다 — 이름처럼 보이는 컬럼명에 속지 말 것(실측 32번).
- **`Atlassian-Plugin-Key`가 설정되면 XML `<component-import>`가 금지된다**(AMPS가 빌드를 막음).
  그래서 Jira 기본 `JiraGlobalPermissionCondition`을 못 쓰고 `AdminOnlyCondition`을 직접 만들었다.
  `<Spring-Context>*</Spring-Context>`와 빈 없는 `plugin-context.xml`은 web fragment의
  condition을 주입할 컨테이너를 만들기 위한 것이다 — 지우면 web-item이 비활성화된다.
- **`CustomFieldManager.getCustomFieldObjects()`는 타입 제공 앱이 죽은 필드를 빠뜨린다**
  (실측: 11개 중 5개만). 존재의 근거는 `customfield` 테이블이다.
- **`ActionDescriptor.getPostFunctions()`는 Jira UI의 "후처리 함수"가 아니다.**
  그건 `getUnconditionalResult()` / `getConditionalResults()`의 post-functions에 있다.
  여기를 빼면 가장 흔한 참조를 통째로 놓친다.
- **워크플로 조건은 AND/OR로 중첩된다.** `RestrictionDescriptor.getConditionsDescriptor()`를
  재귀로 내려가야 한다.
- **숫자 ID 단독 매칭은 오탐이 크다.** 해결책/상태/화면/스텝 ID가 커스텀 필드와 같은
  10000번대를 쓴다. 확정 참조(`customfield_<id>`)와 추정 참조를 분리하고, Jira 기본
  구현에서는 숫자 단독 탐색을 끈다.
- **필드 설정·컨텍스트는 사용 증거가 아니다.** 모든 필드가 자동으로 갖는다.
  판정에서 빼지 않으면 [미사용]이 영원히 안 나온다.
- **라벨 타입은 값을 `label` 테이블에 저장한다.** `customfieldvalue`만 보면 "0건"이 된다.
- **앱이 잠근 필드(Sprint / Epic Link / Rank / Team / Development)를 놓치면 [미사용]로 찍힌다.**
  값은 `AO_*`에, 화면 참조는 없어서 우리 집계로는 값 0·화면 0이다. `managedconfigurationitem`
  테이블을 읽어 `ReferenceType.MANAGED`로 등록하고, `LOCKED`면 위험으로 센다.
  API(`ManagedConfigurationItemService`)는 `CustomField` 객체를 요구해서 앱이 죽으면 못 쓴다 —
  테이블은 앱 상태와 무관하게 남는다.
- **`managedconfigurationitem.managed`는 boolean이 아니라 `varchar(10)`의 `"true"`다**
  (OfBiz indicator). SQL에서 비교하면 PostgreSQL이 거부한다. Java에서 판정한다.
- **`changeitem.field`는 필드 이름 문자열이다.** 이름이 중복이면 이력이 섞인다 →
  중복 이름 필드는 마지막 변경일을 "부정확"으로 표시하고 값을 붙이지 않는다.
- **Velocity는 문자열을 enum으로 변환하지 못한다.** `FieldUsage.getRefCount(String)` /
  `getRefs(String)`가 템플릿용 입구다.
- **Velocity 는 `hasX()` 를 프로퍼티로 인식하지 않는다.** `$action.hasFoo` 는
  `getHasFoo()`/`isHasFoo()` 를 찾고, 없으면 조용히 false 다 — 구역 전체가 사라진다.
  새 접근자는 `get`/`is` 로 시작할 것(실측 23·36번. 같은 함정을 두 번 밟았다).
- **`catch (RuntimeException)`으로는 부족하다.** 8.13으로 컴파일해 8.17.1에서 돌리므로
  그 가정이 깨질 때 나오는 것은 `NoSuchMethodError` / `NoClassDefFoundError` /
  `AbstractMethodError` — 전부 `Error`다. 즉 **가장 현실적인 실패 모드가 유일하게
  안 잡히는 예외**였다. 놓치면 `finally`가 running만 내리고 진행률은 RUNNING에 박혀
  화면이 1.5초마다 영구 폴링한다(관리자는 이유를 못 본다). 스캔 경로는 `Throwable`을 잡는다.
- **Velocity는 없는 메서드를 에러로 만들지 않는다.** 두 가지로 나타난다.
  - 없는 오버로드를 2-arg로 부르면 `$action.text("...", $x)` 가 문자열로 렌더된다.
  - **조건식에서 그러면 분기 전체가 조용히 죽는다.** `hasErrorMessages()` 는 webwork1에
    없는 이름이라(실제는 `getHasErrorMessages()`) `#if` 가 항상 false였고, 목록 화면의
    에러 안내가 v1.0.0부터 한 번도 표시되지 않았다. `hasAnyErrors()` 를 쓴다.
  화면 문구를 넣을 때는 렌더된 HTML에 실제로 나오는지 확인할 것.
- **`{0}` 이 있는 i18n 문구는 반드시 2-arg로 부른다.** `text(key)` 로 부르면 치환이
  안 되고 `{0}` 이 화면에 나온다. `{0}` 을 가진 키를 grep으로 훑어 호출부를 대조할 것
  (예외: `janitor.fields.inject.*` 는 템플릿 문자열을 JS로 넘겨 클라이언트가 치환한다).
- **`LIKE`의 `_`는 와일드카드다.** `'customfield_%'`는 의도한 쿼리가 아니다 →
  `ESCAPE`를 준다.
- **우리 패키지의 `INFO` 로그는 테스트 인스턴스에서 버려진다.** 남는 것은 WARN·ERROR 뿐이다.
  나중에 확인해야 하는 사실(스냅샷 복원 여부 같은 것)은 WARN 으로 남긴다(실측 33번).
- **i18n `.properties`는 ISO-8859-1로 읽힌다.** 한글은 `\uXXXX`로 escape해야 한다.
  `i18n/*.properties.src`(UTF-8)를 고치고 `tools/make-i18n.py`로 생성한다.
  `src/main/resources/janitor*.properties`를 직접 고치지 말 것.

## 검증 방법

```bash
# 위 세 환경변수(JIRA_BASE / JIRA_USER / JIRA_PASS)가 export 되어 있다고 본다.
#   JIRA_SECOND_PASS = 픽스처가 만드는 비관리자 계정의 비밀번호. 안 주면 매번 무작위로
#                      만든다 — 그 계정으로 로그인해 권한 테스트를 할 때만 지정한다.
python3 tools/make-testdata.py
./tools/make-workflow-fixture.sh
curl -s -u "$JIRA_USER:$JIRA_PASS" -H 'X-Atlassian-Token: no-check' -X POST \
     "$JIRA_BASE/rest/janitor/1.0/scan"
curl -s -u "$JIRA_USER:$JIRA_PASS" "$JIRA_BASE/rest/janitor/1.0/fields" \
  | python3 -c "import json,sys;[print(f['id'],f['status'],f['name']) for f in json.load(sys.stdin)['fields']]"

# 값 집계 교차 확인 (플러그인 = DB = JQL 이어야 한다)
docker exec <db-container> psql -U "$PGUSER" -d jira -c \
  "SELECT customfield, COUNT(DISTINCT issue), COUNT(*) FROM customfieldvalue GROUP BY customfield;"
curl -s -u "$JIRA_USER:$JIRA_PASS" --get --data-urlencode "jql=cf[10302] is not EMPTY" \
     --data-urlencode "maxResults=0" "$JIRA_BASE/rest/api/2/search"

# 권한: 비관리자는 전부 403, 익명은 401
curl -s -u "janitor-tester:$JIRA_SECOND_PASS" -o /dev/null -w '%{http_code}\n' \
     "$JIRA_BASE/rest/janitor/1.0/fields"

# 중복 실행 방지: 하나만 202, 나머지는 409
for i in 1 2 3; do (curl -s -o /dev/null -w "$i=%{http_code}\n" -u "$JIRA_USER:$JIRA_PASS" \
  -H 'X-Atlassian-Token: no-check' -X POST "$JIRA_BASE/rest/janitor/1.0/scan") & done; wait
```

## 8.17.1 에서 돌려보기 (검증용)

운영 타깃은 8.17.1인데 컴파일은 8.13.0으로 한다. 그 가정을 실측으로 확인하는 방법이다.

```bash
sleep infinity | atlas-run -Pjira817     # http://localhost:2990/jira  admin/admin
./tools/probe-jira.sh http://localhost:2990/jira admin admin      # 대조 항목 수집
./tools/probe-jira.sh "$JIRA_BASE" "$JIRA_USER" "$JIRA_PASS"     # 8.13 기준값
```

- **`sleep infinity |` 가 필요하다.** `atlas-run`은 stdin에서 Ctrl-D를 기다리는데,
  백그라운드로 띄우면 stdin이 즉시 EOF가 되어 기동 직후 정상 종료해 버린다(실측:
  "started successfully in 143s" 다음 줄이 곧바로 종료였다). 파이프로 stdin을 붙잡아 둔다.
- AMPS 8.1.2의 jira-maven-plugin에는 **`start` goal이 없다** (`run` / `debug` / `stop`뿐).
  그래서 블로킹을 피할 수 없다.
- **8.17.1 인스턴스가 떠 있는 동안 `build.sh`를 돌리지 말 것.** `mvn clean`이 `target/`을
  지우는데 실행 중인 인스턴스의 홈(`target/jira/home`)이 그 안에 있다.
- 라이선스는 AMPS가 개발용 timebomb을 자동으로 넣는다. **키가 필요 없다.**
  대신 수 시간마다 만료되므로 장시간 세션에는 재기동이 필요하다.
- DB는 **H2**다. PostgreSQL 방언 검증은 docker 8.13 인스턴스가 담당한다.
  두 환경이 서로 다른 축을 덮으므로 어느 하나로 대체되지 않는다
  (실측 사례: `managedconfigurationitem.managed`의 varchar/boolean 문제는
  PostgreSQL에서만 드러났다).
- 픽스처는 REST 기반이라 그대로 재사용된다:
  `JIRA_BASE=http://localhost:2990/jira JIRA_USER=admin JIRA_PASS=admin python3 tools/make-testdata.py`

대조 결과는 docs/01-버전대조-8.13-vs-8.17.1.md 에 있다.

## 테스트 환경에서 Jira Software가 꺼져 있을 때

`installed-plugins`의 앱 jar 버전이 Jira 버전과 어긋나면 OSGi 해석이 실패해
Jira Agile / Jira Software Application이 비활성으로 남는다. 그러면 Epic Link /
Sprint / Rank 가 Jira 관리 화면에도 안 보인다.

**이 도구는 영향을 받지 않는다** — 필드의 존재 근거를 `customfield` 테이블에서
가져오기 때문이다. 오히려 그 상태가 "타입 제공 앱이 죽은 필드" 경로를 검증해 준다
(docs/00-환경실측.md 11번·14번). 애자일 자체의 동작을 테스트해야 할 때만
버전을 맞춰야 한다.

## 남은 일

- ~~v1.5: 이슈 네비게이터 컬럼 레이아웃 집계~~ → v1.1.0 에서 넣었다(실측 32번)
- ~~v1.5: 재시작 후에도 결과 유지~~ → v1.2.0 에서 넣었다(실측 33번). AO 가 아니라
  SAL `PluginSettings` 다 — 이유는 33번.
- ~~v2: 심층 스캔~~ → v1.3.0 에서 넣었다(실측 36번). 결과는 "테이블명 / 행 ID"까지다 —
  "어느 앱인지"는 뺐다. AO 프리픽스로 앱을 알아낼 방법이 없다(실측 35번)
- ~~기존 `inactiveUser_search` 앱을 `users` 모듈로 이관 (기획서 결정 6)~~ →
  **합치지 않기로 했다**(실측 38번). 그 앱은 관리자 화면이 없고 JQL 함수 + Jira REST
  응답을 고쳐 쓰는 서블릿 필터다 — 대상 사용자도(전 사용자 vs 관리자), 성격도
  ("조회 전용"의 반대), 영향 범위도(전 사용자의 자동완성) 다르다. 기술적으로는
  합칠 수 있었다. 근거는 38번에 있다.
