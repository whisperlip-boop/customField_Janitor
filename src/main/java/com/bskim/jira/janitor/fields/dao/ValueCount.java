package com.bskim.jira.janitor.fields.dao;

/**
 * 한 필드의 값 집계. 이슈 수와 행 수를 함께 들고 다닌다.
 *
 * <p>둘을 나누는 이유는 기획서 함정 6이다. {@code customfieldvalue}는 다중 선택
 * 필드에서 이슈 하나에 여러 행을 만든다. "이슈 수"와 "값 행 수"를 섞어 표기하면
 * 관리자가 규모를 잘못 읽는다.
 */
public final class ValueCount {

    private final long issues;
    private final long rows;

    public ValueCount(long issues, long rows) {
        this.issues = issues;
        this.rows = rows;
    }

    public long getIssues() {
        return issues;
    }

    public long getRows() {
        return rows;
    }
}
