package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.rest.JanitorI18n;

/** 확인하지 못한 항목 1건. 기획서 함정 7. */
public class ProblemDto {

    public String area;
    public String target;
    public String message;

    public ProblemDto() {
    }

    public ProblemDto(ScanProblem problem) {
        this.area = problem.getArea();
        this.target = problem.getTarget();
        // 호출자 로케일로 그린다. 저장된 것은 키와 인자다(docs/00 41번).
        this.message = problem.resolve(JanitorI18n.helper());
    }
}
