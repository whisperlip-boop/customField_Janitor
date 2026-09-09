package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.ScanProblem;

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
        this.message = problem.getMessage();
    }
}
