package com.bskim.jira.janitor.fields.deep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 한 필드가 한 앱 테이블에서 문자열로 일치한 결과. <b>참조가 아니라 일치다.</b>
 *
 * <p>행 단위({@code DeepHit})가 아니라 테이블 단위로 묶는 이유(리뷰 지적 셋을 한 번에):
 * 행마다 객체를 만들면 결과 크기에 상한이 없고, 20건에서 잘라 버리면 "몇 건이었는지"가
 * 사라져 화면 제목이 총 건수인 척했고, 같은-테이블 건수를 세느라 목록을 매번 순회했다.
 * 테이블 하나에 {@code matchCount} 와 행 ID 표본만 두면 셋이 함께 풀린다.
 */
public final class DeepTableMatch {

    /** 행 ID 표본 상한. 나머지는 {@link #getMatchCount()} 로만 남는다. */
    public static final int SAMPLE_ROWS = 20;

    private final String table;
    private int matchCount;
    private final List<String> sampleRowIds = new ArrayList<String>();

    public DeepTableMatch(String table) {
        this.table = table;
    }

    /** 일치 행 하나를 더한다. 표본이 차면 건수만 올린다. */
    void add(String rowId) {
        matchCount++;
        if (rowId != null && sampleRowIds.size() < SAMPLE_ROWS) {
            sampleRowIds.add(rowId);
        }
    }

    /** 스냅샷 복원용. */
    DeepTableMatch(String table, int matchCount, List<String> sampleRowIds) {
        this.table = table;
        this.matchCount = matchCount;
        this.sampleRowIds.addAll(sampleRowIds);
    }

    public String getTable() {
        return table;
    }

    /** 이 테이블에서 일치한 행 수 전부(표본을 넘는 것 포함). */
    public int getMatchCount() {
        return matchCount;
    }

    public List<String> getSampleRowIds() {
        return Collections.unmodifiableList(sampleRowIds);
    }

    /** 표본에 안 담긴 행 수. 화면이 "+N" 으로 그린다. */
    public int getOverflow() {
        return Math.max(0, matchCount - sampleRowIds.size());
    }
}
