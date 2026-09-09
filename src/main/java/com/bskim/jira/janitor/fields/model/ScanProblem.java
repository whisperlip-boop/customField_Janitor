package com.bskim.jira.janitor.fields.model;

/**
 * 스캔 중 확인하지 못한 항목 한 건. 기획서 함정 7: 실패를 조용히 누락시키면
 * 도구를 신뢰할 수 없게 된다. 실패는 반드시 "확인 불가" 목록으로 노출한다.
 */
public final class ScanProblem {

    private final String area;
    private final String target;
    private final String message;

    public ScanProblem(String area, String target, String message) {
        this.area = area;
        this.target = target;
        this.message = message;
    }

    /** 어느 수집 단계에서 났는지. 예: "filter", "workflow". */
    public String getArea() {
        return area;
    }

    /** 무엇을 확인하지 못했는지. 예: 필터 이름 + ID. */
    public String getTarget() {
        return target;
    }

    public String getMessage() {
        return message;
    }
}
