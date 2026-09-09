package com.bskim.jira.janitor.fields.model;

/**
 * 기획서 3장의 상태 라벨. 이 라벨이 앱의 정체성이므로 임의로 늘리거나 줄이지 않는다.
 *
 * <p>판정 우선순위: 위험 &gt; 고아 데이터 &gt; 활성 &gt; 방치 &gt; 미사용.
 * 위험 조건이 걸리면 값/화면 상태와 무관하게 위험이다.
 *
 * <p>{@link #cleanupOrder}는 목록 화면의 기본 정렬용이다. 청소 대상이 위로 오게
 * 미사용(0) → 방치(1) → 고아 데이터(2) → 활성(3) → 위험(4) 순서다.
 */
public enum UsageStatus {

    UNUSED("janitor.fields.status.unused", "unused", 0, "success"),
    ABANDONED("janitor.fields.status.abandoned", "abandoned", 1, "moved"),
    ORPHAN_DATA("janitor.fields.status.orphanData", "orphan-data", 2, "current"),
    ACTIVE("janitor.fields.status.active", "active", 3, ""),
    AT_RISK("janitor.fields.status.atRisk", "at-risk", 4, "error");

    private final String i18nKey;
    private final String code;
    private final int cleanupOrder;
    private final String lozengeType;

    UsageStatus(String i18nKey, String code, int cleanupOrder, String lozengeType) {
        this.i18nKey = i18nKey;
        this.code = code;
        this.cleanupOrder = cleanupOrder;
        this.lozengeType = lozengeType;
    }

    public String getI18nKey() {
        return i18nKey;
    }

    /** REST/CSV/JS에서 쓰는 안정적인 식별자. i18n 라벨과 달리 절대 번역하지 않는다. */
    public String getCode() {
        return code;
    }

    public int getCleanupOrder() {
        return cleanupOrder;
    }

    /** AUI lozenge 종류. 빈 문자열이면 기본(회색). */
    public String getLozengeType() {
        return lozengeType;
    }

    /**
     * 세 축의 집계에서 상태를 판정한다. 기획서 3장 그대로다.
     *
     * <p>{@code anyRefs} 대신 "사용 증거 참조 수"를 받는 이유는 실측 때문이다.
     * Jira는 새 커스텀 필드를 만들면 자동으로 모든 필드 설정에 넣고 기본 컨텍스트를
     * 준다. 아무 데도 안 쓴 필드도 참조 2건을 갖게 되므로 "모든 참조 = 0"을 그대로
     * 쓰면 [미사용]이 영원히 나오지 않는다.
     * ({@link ReferenceType#isEvidence()} 주석 참고)
     *
     * <p>기획서 3장이 정의하지 않은 조합이 하나 있다: 값 0 / 화면 0 인데 필터나
     * 가젯에서는 참조되는 필드. 라벨을 새로 만들지 않고(3장: 임의로 늘리지 않는다)
     * [방치]로 본다 — 화면에 없으니 아무도 입력할 수 없고, 그래도 지우면 필터가
     * 깨지므로 [미사용]보다는 [방치]가 관리자에게 정확한 신호다.
     *
     * @param issuesWithValue 값이 있는 이슈 수 (축 A)
     * @param screenRefs      화면 참조 수 (축 B 중 화면만)
     * @param riskyRefs       워크플로 / 권한·알림·이슈보안 스킴 참조 수
     * @param evidenceRefs    사용 증거로 셀 수 있는 참조 수 (필드 설정·컨텍스트 제외)
     */
    public static UsageStatus judge(long issuesWithValue, int screenRefs, int riskyRefs, int evidenceRefs) {
        if (riskyRefs > 0) {
            return AT_RISK;
        }
        if (issuesWithValue > 0) {
            return screenRefs > 0 ? ACTIVE : ORPHAN_DATA;
        }
        if (screenRefs > 0) {
            return ABANDONED;
        }
        return evidenceRefs > 0 ? ABANDONED : UNUSED;
    }
}
