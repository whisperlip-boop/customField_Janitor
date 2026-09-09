package com.bskim.jira.janitor.fields.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 커스텀 필드 한 개의 사용 현황. 목록 화면의 한 행이자 상세 화면의 본체다.
 *
 * <p>기획서 결정 2에 따라 모든 동일성 판단은 {@link #getNumericId()} 기준이다.
 * 이름은 표시용일 뿐이며, 같은 이름이 여러 개 있는 것이 정리 대상 인스턴스의
 * 정상 상태다.
 */
public final class FieldUsage {

    private final long numericId;
    private final String fieldId;
    private final String name;
    private final String typeKey;
    private final String typeName;

    private long issuesWithValue;
    private long valueRows;
    private Date lastValueChange;
    private boolean lastValueChangeAmbiguous;
    private boolean duplicateName;
    private boolean typeAvailable = true;

    private final List<Reference> references = new ArrayList<Reference>();
    /** 중복 검사용. 순서는 {@link #references} 가, 동일성 판정은 이쪽이 담당한다. */
    private final Set<Reference> seen = new HashSet<Reference>();
    private final List<String> contextProjects = new ArrayList<String>();
    private final List<String> contextIssueTypes = new ArrayList<String>();
    private boolean globalContext;

    public FieldUsage(long numericId, String name, String typeKey, String typeName) {
        this.numericId = numericId;
        this.fieldId = "customfield_" + numericId;
        this.name = name;
        this.typeKey = typeKey;
        this.typeName = typeName;
    }

    public long getNumericId() {
        return numericId;
    }

    /** {@code customfield_10001} 형태. 참조 문자열 스캔과 URL 파라미터가 이 형태를 쓴다. */
    public String getFieldId() {
        return fieldId;
    }

    public String getName() {
        return name;
    }

    public String getTypeKey() {
        return typeKey;
    }

    public String getTypeName() {
        return typeName;
    }

    /** 축 A 주 지표. 기획서 5.1의 {@code COUNT(DISTINCT issue)}. */
    public long getIssuesWithValue() {
        return issuesWithValue;
    }

    public void setIssuesWithValue(long issuesWithValue) {
        this.issuesWithValue = issuesWithValue;
    }

    /**
     * 값 행 수. 다중 선택 필드는 이슈 하나가 여러 행을 만들기 때문에
     * 이슈 수와 다를 수 있다(함정 6). 두 값을 모두 들고 다니며 UI에서 구분해 쓴다.
     */
    public long getValueRows() {
        return valueRows;
    }

    public void setValueRows(long valueRows) {
        this.valueRows = valueRows;
    }

    public Date getLastValueChange() {
        return lastValueChange == null ? null : new Date(lastValueChange.getTime());
    }

    public void setLastValueChange(Date lastValueChange) {
        this.lastValueChange = lastValueChange == null ? null : new Date(lastValueChange.getTime());
    }

    /**
     * 마지막 변경일이 신뢰할 수 없는 경우. changeitem.field는 변경 당시의 필드
     * "이름"이므로 이름이 중복되면 다른 필드의 이력이 섞인다(함정 5).
     */
    public boolean isLastValueChangeAmbiguous() {
        return lastValueChangeAmbiguous;
    }

    public void setLastValueChangeAmbiguous(boolean lastValueChangeAmbiguous) {
        this.lastValueChangeAmbiguous = lastValueChangeAmbiguous;
    }

    /** 같은 이름의 커스텀 필드가 둘 이상 존재한다. 목록에서 경고 표시. */
    public boolean isDuplicateName() {
        return duplicateName;
    }

    public void setDuplicateName(boolean duplicateName) {
        this.duplicateName = duplicateName;
    }

    /**
     * 이 필드의 타입을 제공하는 앱이 살아 있는가.
     *
     * <p>거짓이면 Jira API로는 이 필드를 다룰 수 없다(컨텍스트 조회 불가, 화면에
     * 표시 불가). 필드와 값은 DB에 남아 있다. 앱을 지운 뒤 남은 잔해가 이런 모양이고,
     * 정리 도구 입장에서는 가장 중요한 후보다. 목록에 반드시 표시한다.
     */
    public boolean isTypeAvailable() {
        return typeAvailable;
    }

    public void setTypeAvailable(boolean typeAvailable) {
        this.typeAvailable = typeAvailable;
    }

    /**
     * 참조를 붙인다. <b>같은 참조는 두 번 세지 않는다</b>({@link Reference#equals}).
     *
     * <p>한 대상이 같은 필드를 여러 자리에서 가리키면(2차원 통계 가젯의 x/y 축,
     * 워크플로 한 디스크립터의 여러 arg) 같은 참조가 2건으로 세지고, 사용 증거로
     * 세는 종류에서는 [미사용]이 [방치]로 뒤집힌다. 세는 쪽마다 막지 않고 들어오는
     * 입구 한 곳에서 막는다.
     *
     * <p>{@code List} 를 유지하는 이유는 순서다 — 상세 화면은 수집한 순서대로
     * 보여주는 게 읽기 쉽다. 중복 검사는 별도 {@code Set} 으로 한다.
     *
     * <p>선형 검색으로 두면 안 된다. 전이 화면 참조는 (워크플로, 전이)마다 1건씩
     * 생기므로 공용 전이 화면을 쓰는 워크플로 100개 × 전이 20개면 한 필드에
     * 2,000건이고 비교가 200만 회다 — 워크플로가 많은 인스턴스에서 스캔 시간에
     * 드러난다. {@code hashCode} 가 이미 있으니 {@code Set} 하나로 O(1)이 된다.
     */
    public void addReference(Reference reference) {
        if (reference == null || !seen.add(reference)) {
            return;
        }
        references.add(reference);
    }

    public List<Reference> getReferences() {
        return Collections.unmodifiableList(references);
    }

    public List<Reference> getReferences(ReferenceType type) {
        List<Reference> hits = new ArrayList<Reference>();
        for (Reference reference : references) {
            if (reference.getType() == type) {
                hits.add(reference);
            }
        }
        return hits;
    }

    public Map<ReferenceType, Integer> getReferenceCounts() {
        Map<ReferenceType, Integer> counts = new EnumMap<ReferenceType, Integer>(ReferenceType.class);
        for (ReferenceType type : ReferenceType.values()) {
            counts.put(type, 0);
        }
        for (Reference reference : references) {
            counts.put(reference.getType(), counts.get(reference.getType()) + 1);
        }
        return counts;
    }

    public int getReferenceCount(ReferenceType type) {
        return getReferences(type).size();
    }

    /**
     * Velocity 템플릿용. Velocity는 문자열을 enum으로 강제 변환하지 못하므로
     * enum 이름을 받는 입구를 따로 둔다. 알 수 없는 이름이면 0이다.
     */
    public int getRefCount(String typeName) {
        try {
            return getReferenceCount(ReferenceType.valueOf(typeName));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** Velocity 템플릿용. {@link #getRefCount(String)}과 같은 이유. */
    public List<Reference> getRefs(String typeName) {
        try {
            return getReferences(ReferenceType.valueOf(typeName));
        } catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    public int getTotalReferenceCount() {
        return references.size();
    }

    /**
     * 실제 사용의 증거로 셀 수 있는 참조 수. 필드 설정과 컨텍스트는 모든 필드가
     * 자동으로 갖게 되므로 제외한다({@link ReferenceType#isEvidence()} 참고).
     */
    public int getEvidenceReferenceCount() {
        int count = 0;
        for (Reference reference : references) {
            if (reference.getType().isEvidence()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Jira가 잠근 필드인가. 참이면 Jira 자신도 삭제 메뉴를 숨기므로 우리도 숨긴다
     * (기획서 결정 1: 실행은 Jira 표준 화면에서 한다 — 그 화면에 삭제가 없으면
     * 우리 화면에 링크를 두는 것은 거짓말이다).
     */
    public boolean isLocked() {
        for (Reference reference : references) {
            if (reference.getType() == ReferenceType.MANAGED && reference.isRisky()) {
                return true;
            }
        }
        return false;
    }

    public int getRiskyReferenceCount() {
        int count = 0;
        for (Reference reference : references) {
            if (reference.isRisky()) {
                count++;
            }
        }
        return count;
    }

    public List<String> getContextProjects() {
        return Collections.unmodifiableList(contextProjects);
    }

    public List<String> getContextIssueTypes() {
        return Collections.unmodifiableList(contextIssueTypes);
    }

    public boolean isGlobalContext() {
        return globalContext;
    }

    public void setGlobalContext(boolean globalContext) {
        this.globalContext = globalContext;
    }

    public void addContextProject(String project) {
        if (!contextProjects.contains(project)) {
            contextProjects.add(project);
        }
    }

    public void addContextIssueType(String issueType) {
        if (!contextIssueTypes.contains(issueType)) {
            contextIssueTypes.add(issueType);
        }
    }

    public UsageStatus getStatus() {
        return UsageStatus.judge(issuesWithValue,
                getReferenceCount(ReferenceType.SCREEN),
                getRiskyReferenceCount(),
                getEvidenceReferenceCount());
    }

    /** 상세 화면 하단의 판단 요약 한 줄에 쓸 i18n 키. 기획서 6.2. */
    public String getVerdictI18nKey() {
        switch (getStatus()) {
            case AT_RISK:
                return "janitor.fields.verdict.atRisk";
            case ORPHAN_DATA:
                return "janitor.fields.verdict.orphanData";
            case ACTIVE:
                return "janitor.fields.verdict.active";
            case ABANDONED:
                return "janitor.fields.verdict.abandoned";
            default:
                return "janitor.fields.verdict.unused";
        }
    }

    /** 필드 설정 관리 화면으로 가는 링크. 기획서 결정 1: 삭제는 Jira 표준 화면에서 한다. */
    public String getConfigureUrl() {
        return "/secure/admin/ConfigureCustomField!default.jspa?customFieldId=" + numericId;
    }

    public String getDeleteUrl() {
        return "/secure/admin/DeleteCustomField!default.jspa?id=" + numericId;
    }
}
