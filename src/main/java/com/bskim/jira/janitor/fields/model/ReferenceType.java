package com.bskim.jira.janitor.fields.model;

/**
 * 참조가 발견된 장소의 종류. 기획서 5.3의 수집 대상과 1:1로 대응한다.
 *
 * <p>{@link #risky}가 참인 종류가 하나라도 있으면 그 필드는 [위험]이다.
 * 근거: 이 참조들은 삭제 시 화면이 아니라 "동작"이 조용히 깨지는 곳이다.
 *
 * <p>{@link #evidence}는 "이 참조가 실제 사용의 증거인가"다. 필드 설정과 컨텍스트는
 * 거짓이다 — Jira는 커스텀 필드를 만들면 자동으로 모든 필드 설정에 넣고 기본
 * 컨텍스트를 준다. 실측: 아무 데도 안 쓴 새 필드도 필드 설정 참조 1건과 컨텍스트
 * 참조 1건을 갖는다. 이것을 사용 증거로 세면 [미사용] 라벨이 영원히 나오지 않고,
 * 그러면 3장의 "청소 1차 대상"이라는 라벨의 존재 이유가 사라진다.
 *
 * <p>두 종류를 아예 수집하지 않는 것이 아니라 판정에서만 빼는 이유: 필드 설정의
 * 숨김/필수 표시와 컨텍스트의 적용 범위는 삭제 판단에 실제로 필요한 정보다
 * (필수로 걸린 필드를 지우면 이슈 생성이 막힌다). 보여는 주고 세지는 않는다.
 */
public enum ReferenceType {

    /**
     * 앱이 관리하는(잠긴) 필드. 기획서 3장이 정의한 [위험]의 트리거에 없던 종류다.
     *
     * <p>추가한 근거: [위험]의 정의는 "삭제하면 기능이 조용히 깨진다"이고, Sprint나
     * Epic Link를 지우면 Jira Software 보드가 정확히 그렇게 깨진다. Jira 자신도 이
     * 필드들의 삭제 메뉴를 숨긴다. 그런데 이 필드들은 값이 {@code AO_*} 테이블에 있어서
     * 우리 집계에는 0으로 나오고, 화면 참조도 없는 경우가 많다 —
     * 그대로 두면 초록 [미사용]("삭제 안전, 청소 1차 대상")으로 찍힌다(실측).
     * 정리 도구가 관리자에게 낼 수 있는 가장 나쁜 신호다.
     *
     * <p>라벨을 새로 만들지 않고 참조 종류를 하나 더 두는 쪽을 택했다 — 기획서 3장의
     * "라벨을 임의로 늘리지 않는다"를 지키면서 판정 규칙({@code judge()})도 손대지 않는다.
     *
     * <p>위험 여부는 종류가 아니라 건별로 정한다. {@code LOCKED}만 위험이고
     * {@code ADMIN}/{@code SYS_ADMIN} 수준은 "앱이 관리하지만 관리자가 바꿀 수 있다"는
     * 뜻이라 Jira도 삭제를 막지 않는다.
     */
    MANAGED("janitor.fields.ref.managed", "managed", false, true),

    SCREEN("janitor.fields.ref.screen", "screen", false, true),
    FIELD_CONFIG("janitor.fields.ref.fieldConfig", "fieldConfig", false, false),
    CONTEXT("janitor.fields.ref.context", "context", false, false),
    WORKFLOW("janitor.fields.ref.workflow", "workflow", true, true),
    FILTER("janitor.fields.ref.filter", "filter", false, true),
    PERMISSION_SCHEME("janitor.fields.ref.permissionScheme", "permissionScheme", true, true),
    NOTIFICATION_SCHEME("janitor.fields.ref.notificationScheme", "notificationScheme", true, true),
    ISSUE_SECURITY_SCHEME("janitor.fields.ref.issueSecurityScheme", "issueSecurityScheme", true, true),
    GADGET("janitor.fields.ref.gadget", "gadget", false, true),

    /**
     * 이슈 네비게이터 컬럼 설정(기획서 5.3(9)). 시스템 기본 컬럼 · 필터의 컬럼 ·
     * 사용자 개인 컬럼 셋을 한 종류로 묶는다.
     *
     * <p>사용 증거로 센다. 누군가 이 필드를 목록에 띄우려고 <b>직접 골라 넣은</b>
     * 것이기 때문이다 — 필드 설정·컨텍스트처럼 만들면 자동으로 생기는 것이 아니다.
     * (실측: Jira 가 기본 컬럼에 넣어두는 커스텀 필드는 Development 하나뿐이고,
     * 그 필드는 앱이 잠근 필드라 이미 [위험]이다.)
     *
     * <p>위험은 아니다. 지워도 목록에서 컬럼 하나가 빠질 뿐 동작이 깨지지 않는다.
     */
    COLUMN_LAYOUT("janitor.fields.ref.columnLayout", "columnLayout", false, true);

    private final String i18nKey;
    private final String code;
    private final boolean risky;
    private final boolean evidence;

    ReferenceType(String i18nKey, String code, boolean risky, boolean evidence) {
        this.i18nKey = i18nKey;
        this.code = code;
        this.risky = risky;
        this.evidence = evidence;
    }

    public String getI18nKey() {
        return i18nKey;
    }

    public String getCode() {
        return code;
    }

    public boolean isRisky() {
        return risky;
    }

    /** 실제 사용의 증거로 셀 수 있는 종류인가. 클래스 주석의 근거 참고. */
    public boolean isEvidence() {
        return evidence;
    }
}
