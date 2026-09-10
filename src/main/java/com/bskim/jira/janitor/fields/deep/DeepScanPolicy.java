package com.bskim.jira.janitor.fields.deep;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 심층 스캔의 상한과 예외. 한 곳에 두는 이유: DAO 가 쓰고, 화면과 REST 가 보여주고,
 * 스냅샷 판이 기대는 숫자들이다. 전에는 DAO 에 있어서 REST DTO 가 {@code dao} 패키지를
 * 직접 읽었다(리뷰 지적 — 층 위반).
 *
 * <p>이 값들이 바뀌면 결과의 뜻이 바뀐다({@code SAMPLE_ROWS} 는 표본 수, {@code ROW_LIMIT}
 * 은 "잘렸다"의 기준). 그때는 {@link DeepResultCodec#SCHEMA} 도 올린다.
 */
public final class DeepScanPolicy {

    /**
     * 훑지 않는 프리픽스. 지금은 감사 로그 하나다(docs/00 35번).
     *
     * <p>이 예외는 "해석하지 않는다"는 원칙이 굽는 유일한 자리다. 그래서 화면에
     * 건너뛴 사실을 표시한다 — 조용히 빼면 표가 완전한 것으로 읽힌다.
     */
    public static final Set<String> SKIPPED_PREFIXES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("AO_C77861")));

    /** 한 테이블에서 읽어 올 일치 행의 상한. 넘으면 그 테이블은 "잘렸다"로 기록된다. */
    public static final int ROW_LIMIT = 500;

    /** 쿼리 하나에 허용하는 시간. 넘으면 그 테이블만 포기하고 다음으로 간다. */
    public static final int QUERY_TIMEOUT_SECONDS = 60;

    /** 한 필드·한 테이블당 행 ID 표본 상한. 나머지는 건수로만 남는다. */
    public static final int SAMPLE_ROWS = 20;

    private DeepScanPolicy() {
    }
}
