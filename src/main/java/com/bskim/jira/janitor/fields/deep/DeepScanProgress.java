package com.bskim.jira.janitor.fields.deep;

import java.util.Date;

/**
 * 심층 스캔의 진행률. 일반 스캔은 단계가 고정이라 열거형이지만, 여기는 <b>테이블 수가
 * 인스턴스마다 다르다</b> — 몇 번째 테이블인지가 곧 진행률이다.
 */
public final class DeepScanProgress {

    public enum State { IDLE, RUNNING, DONE, FAILED }

    private final State state;
    private final int tableIndex;
    private final int tableTotal;
    private final String tableName;
    private final Date startedAt;
    private final String error;

    public DeepScanProgress(State state, int tableIndex, int tableTotal, String tableName,
                            Date startedAt, String error) {
        this.state = state;
        this.tableIndex = tableIndex;
        this.tableTotal = tableTotal;
        this.tableName = tableName;
        this.startedAt = startedAt == null ? null : new Date(startedAt.getTime());
        this.error = error;
    }

    public static DeepScanProgress idle() {
        return new DeepScanProgress(State.IDLE, 0, 0, null, null, null);
    }

    public State getState() {
        return state;
    }

    public int getTableIndex() {
        return tableIndex;
    }

    public int getTableTotal() {
        return tableTotal;
    }

    public String getTableName() {
        return tableName;
    }

    public Date getStartedAt() {
        return startedAt == null ? null : new Date(startedAt.getTime());
    }

    public String getError() {
        return error;
    }

    public int getPercent() {
        return tableTotal == 0 ? 0 : (int) Math.round(100.0 * tableIndex / tableTotal);
    }
}
