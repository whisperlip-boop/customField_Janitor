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

    /**
     * 필드 목록을 {@code customfield} 테이블이 아니라 {@code CustomFieldManager}
     * 목록만으로 만들었는가.
     *
     * <p>참이면 <b>판정 대상 자체가 빠져 있다.</b> Jira API 는 타입 제공 앱이 비활성인
     * 필드를 빼고 주는데(docs/00 3번·11번) 그게 바로 정리 1순위 후보다. 라벨이
     * 뒤집히는 것은 아니라서 스캔을 실패시키지는 않지만, "확인 불가" 목록 한 줄로
     * 두면 표가 완전하다고 읽힌다 — 실패의 성격이 "일부 항목을 못 읽었다"가 아니라
     * "목록이 반쪽이다"라서 화면 맨 위에 있어야 한다.
     */
    private final boolean fieldListDegraded;

    public ScanResult(Date startedAt, Date finishedAt, List<FieldUsage> fields, List<ScanProblem> problems) {
        this(startedAt, finishedAt, fields, problems, false);
    }

    public ScanResult(Date startedAt, Date finishedAt, List<FieldUsage> fields, List<ScanProblem> problems,
                      boolean fieldListDegraded) {
        this.fieldListDegraded = fieldListDegraded;
        this.startedAt = new Date(startedAt.getTime());
        this.finishedAt = new Date(finishedAt.getTime());
        this.durationMs = finishedAt.getTime() - startedAt.getTime();
        this.fields = Collections.unmodifiableList(new ArrayList<FieldUsage>(fields));
        this.problems = Collections.unmodifiableList(new ArrayList<ScanProblem>(problems));
    }

    public boolean isFieldListDegraded() {
        return fieldListDegraded;
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
