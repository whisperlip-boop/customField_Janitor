package com.bskim.jira.janitor.fields.deep;

import com.bskim.jira.janitor.fields.model.ScanProblem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 심층 스캔 한 번의 결과. 일반 스캔 결과({@code ScanResult})와 <b>섞지 않는다.</b>
 *
 * <p>섞지 않는 이유가 둘이다.
 * <ol>
 *   <li>여기 담긴 것은 참조가 아니라 <b>문자열 일치</b>다. 라벨 판정({@code judge()})에
 *       들어가면 "앱 설정 어딘가에 필드 ID 문자열이 있다"가 "쓰이고 있다"로 승격된다.
 *       그건 해석이고, 이 기능은 해석하지 않기로 한 기능이다.</li>
 *   <li>심층 스캔은 일반 스캔과 다른 때에 따로 돌아간다. 이미 끝난 불변
 *       {@code ScanResult} 에 나중에 붙일 수도 없다.</li>
 * </ol>
 */
public final class DeepScanResult {

    private final Date startedAt;
    private final Date finishedAt;
    private final int tablesScanned;
    private final List<String> skippedPrefixes;
    private final long rowsCounted;
    private final Map<Long, List<DeepHit>> hitsByField;
    private final List<ScanProblem> problems;

    public DeepScanResult(Date startedAt, Date finishedAt, int tablesScanned,
                          List<String> skippedPrefixes, long rowsCounted,
                          Map<Long, List<DeepHit>> hitsByField, List<ScanProblem> problems) {
        this.startedAt = new Date(startedAt.getTime());
        this.finishedAt = new Date(finishedAt.getTime());
        this.tablesScanned = tablesScanned;
        this.skippedPrefixes = Collections.unmodifiableList(new ArrayList<String>(skippedPrefixes));
        this.rowsCounted = rowsCounted;
        this.hitsByField = Collections.unmodifiableMap(new LinkedHashMap<Long, List<DeepHit>>(hitsByField));
        this.problems = Collections.unmodifiableList(new ArrayList<ScanProblem>(problems));
    }

    public Date getStartedAt() {
        return new Date(startedAt.getTime());
    }

    public Date getFinishedAt() {
        return new Date(finishedAt.getTime());
    }

    public int getTablesScanned() {
        return tablesScanned;
    }

    /** 일부러 건너뛴 프리픽스(감사 로그). 화면에 그대로 보여준다 — 숨기면 안 된다. */
    public List<String> getSkippedPrefixes() {
        return skippedPrefixes;
    }

    public long getRowsCounted() {
        return rowsCounted;
    }

    public List<ScanProblem> getProblems() {
        return problems;
    }

    public Map<Long, List<DeepHit>> getHitsByField() {
        return hitsByField;
    }

    public List<DeepHit> getHits(long fieldNumericId) {
        List<DeepHit> hits = hitsByField.get(fieldNumericId);
        return hits == null ? Collections.<DeepHit>emptyList() : hits;
    }

    public int getFieldCount() {
        return hitsByField.size();
    }
}
