package com.bskim.jira.janitor.fields.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "이 필드가 여기서 쓰인다"는 한 건. 기획서 5.3의 Reference 형태
 * { 종류, 대상 이름, 대상 ID, 상세, 관리화면 링크 } 에 프로젝트 체인과 위험 여부를 더했다.
 *
 * <p>프로젝트 체인이 별도 필드인 이유: 기획서 5.3(2)의 "이 필드는 결국 A, B, C
 * 프로젝트의 생성/편집 화면에 나타남"이 기본 UI가 안 주는 핵심 가치라서,
 * 상세 문자열에 뭉개지 않고 구조를 유지한다.
 *
 * <p>위험 여부가 종류와 별개인 이유: 기획서 6.2는 전이 화면을 워크플로 항목으로
 * 묶어 보여주라고 하지만, 3장의 [위험]은 "조건/검증기/후처리 함수"에만 걸린다.
 * 전이 화면에 필드가 있다고 [위험]으로 올리면 라벨이 무의미해진다 — 전이 화면에서
 * 필드가 빠지는 것은 기능이 조용히 깨지는 것과 다르다. 그래서 같은 워크플로 종류
 * 안에서도 건별로 위험 여부를 구분한다.
 */
public final class Reference {

    private final ReferenceType type;
    private final String targetName;
    private final String targetId;
    private final String detail;
    private final String detailI18nKey;
    private final String adminUrl;
    private final List<String> projects;
    private final boolean risky;

    public Reference(ReferenceType type, String targetName, String targetId, String detail, String adminUrl) {
        this(type, targetName, targetId, detail, null, adminUrl, null, type.isRisky());
    }

    public Reference(ReferenceType type, String targetName, String targetId, String detail, String adminUrl,
                     List<String> projects) {
        this(type, targetName, targetId, detail, null, adminUrl, projects, type.isRisky());
    }

    public Reference(ReferenceType type, String targetName, String targetId, String detail, String detailI18nKey,
                     String adminUrl, List<String> projects, boolean risky) {
        this.type = type;
        this.targetName = targetName;
        this.targetId = targetId;
        this.detail = detail;
        this.detailI18nKey = detailI18nKey;
        this.adminUrl = adminUrl;
        this.projects = projects == null ? Collections.<String>emptyList() : new ArrayList<String>(projects);
        this.risky = risky;
    }

    public ReferenceType getType() {
        return type;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getTargetId() {
        return targetId;
    }

    /** 위치·조건 등 보조 설명. 없으면 null. */
    public String getDetail() {
        return detail;
    }

    /** 상세를 번역해야 하는 경우의 i18n 키. 없으면 null이고 {@link #getDetail()}을 그대로 쓴다. */
    public String getDetailI18nKey() {
        return detailI18nKey;
    }

    /** 해당 Jira 관리 화면으로 가는 상대 경로. 갈 곳이 없으면 null. */
    public String getAdminUrl() {
        return adminUrl;
    }

    public List<String> getProjects() {
        return Collections.unmodifiableList(projects);
    }

    /** 이 참조 하나만으로 필드를 [위험]으로 만들어야 하는가. */
    public boolean isRisky() {
        return risky;
    }

    /**
     * 같은 참조인가. 같으면 {@link FieldUsage#addReference} 가 두 번 세지 않는다.
     *
     * <p>왜 필요한가: 한 대상이 같은 필드를 여러 자리에서 가리키는 경우가 있다.
     * 2차원 통계 가젯의 {@code xstattype}/{@code ystattype}, 워크플로 한
     * 디스크립터의 여러 {@code arg} 값이 그렇다. 그러면 같은 참조가 2건으로 세지고,
     * {@code GADGET} 처럼 사용 증거로 세는 종류에서는 <b>[미사용]이 [방치]로
     * 뒤집힌다.</b> 상세 화면에도 똑같은 행이 두 줄 나온다.
     *
     * <p>비교에 {@code projects} 와 {@code adminUrl} 은 넣지 않는다 — 같은 대상에서
     * 파생된 값이라 판단에 새 정보를 주지 않고, 넣으면 순서 차이만으로 중복이
     * 살아남는다. {@code risky} 는 넣는다: 같은 워크플로의 전이 화면 참조와
     * 조건 참조는 위험도가 달라 서로 다른 참조다.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Reference)) {
            return false;
        }
        Reference that = (Reference) other;
        return type == that.type
                && risky == that.risky
                && eq(targetId, that.targetId)
                && eq(targetName, that.targetName)
                && eq(detail, that.detail)
                && eq(detailI18nKey, that.detailI18nKey);
    }

    @Override
    public int hashCode() {
        int result = type == null ? 0 : type.hashCode();
        result = 31 * result + (risky ? 1 : 0);
        result = 31 * result + (targetId == null ? 0 : targetId.hashCode());
        result = 31 * result + (targetName == null ? 0 : targetName.hashCode());
        result = 31 * result + (detail == null ? 0 : detail.hashCode());
        result = 31 * result + (detailI18nKey == null ? 0 : detailI18nKey.hashCode());
        return result;
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
