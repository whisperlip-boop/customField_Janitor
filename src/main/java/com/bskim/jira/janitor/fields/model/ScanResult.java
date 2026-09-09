package com.bskim.jira.janitor.fields.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 스캔 한 번의 결과 전체. 메모리에 이것 하나만 들고 있으면 목록/상세/CSV/주입이
 * 모두 여기서 나온다(기획서 결정 3, 9장).
 *
 * <p>불변 객체다. 스캔이 끝날 때 통째로 교체하므로 조회 중 반쪽 상태를 볼 일이 없다.
 */
public final class ScanResult {

    private final Date startedAt;
    private final Date finishedAt;
    private final long durationMs;
    private final List<FieldUsage> fields;
    private final List<ScanProblem> problems;

    public ScanResult(Date startedAt, Date finishedAt, List<FieldUsage> fields, List<ScanProblem> problems) {
        this.startedAt = new Date(startedAt.getTime());
        this.finishedAt = new Date(finishedAt.getTime());
        this.durationMs = finishedAt.getTime() - startedAt.getTime();
        this.fields = Collections.unmodifiableList(new ArrayList<FieldUsage>(fields));
        this.problems = Collections.unmodifiableList(new ArrayList<ScanProblem>(problems));
    }

    public Date getStartedAt() {
        return new Date(startedAt.getTime());
    }

    public Date getFinishedAt() {
        return new Date(finishedAt.getTime());
    }

    public long getDurationMs() {
        return durationMs;
    }

    public List<FieldUsage> getFields() {
        return fields;
    }

    public List<ScanProblem> getProblems() {
        return problems;
    }

    public FieldUsage getField(long numericId) {
        for (FieldUsage field : fields) {
            if (field.getNumericId() == numericId) {
                return field;
            }
        }
        return null;
    }
}
