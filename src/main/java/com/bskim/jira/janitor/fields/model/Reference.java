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
}
