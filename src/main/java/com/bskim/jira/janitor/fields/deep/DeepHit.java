package com.bskim.jira.janitor.fields.deep;

/**
 * 심층 스캔이 찾은 문자열 일치 하나.
 *
 * <p><b>참조가 아니라 일치다.</b> 이 테이블이 무엇을 뜻하는지, 그 행이 살아 있는
 * 설정인지 이력인지 우리는 모른다. 그래서 담는 것도 "어느 테이블 어느 행"까지다.
 */
public final class DeepHit {

    private final String table;
    private final String rowId;

    public DeepHit(String table, String rowId) {
        this.table = table;
        this.rowId = rowId;
    }

    public String getTable() {
        return table;
    }

    /** 기본키가 없는 테이블이면 null. */
    public String getRowId() {
        return rowId;
    }
}
