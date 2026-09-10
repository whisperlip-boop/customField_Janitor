package com.bskim.jira.janitor.fields.store;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 저장된 스냅샷을 메모리의 마지막 결과·실패 위에 얹는 규칙. 일반 스캔과 심층 스캔이 같은 규칙을
 * 써야 하므로 한 곳에 둔다.
 *
 * <ul>
 * <li><b>결과</b>는 메모리에 없으면 복원한다. 방금 실패했더라도 배너가 가리킬 옛 표가 필요하다.</li>
 * <li><b>실패</b>는 이 JVM 이 아직 아무것도 만들지 않았을 때만 복원한다. 저장소가
 * startScan() 때는 닿지 않고 saveSnapshot() 때 닿았다면 첫 복원이 방금 성공한 결과 위에서
 * 돈다 — 그때 옛 실패를 올리면 "마지막 스캔이 실패했습니다"가 방금 성공한 스캔 뒤에 뜬다.</li>
 * </ul>
 *
 * 판정은 결과를 넣기 <em>전에</em> 한다. 넣은 뒤에 보면 늘 "만든 것이 있다"가 되어 실패가
 * 재시작 후에 한 번도 복원되지 않는다(실측).
 */
public final class SnapshotMerge {

    private SnapshotMerge() {
    }

    public static <R, F> void apply(AtomicReference<R> result, AtomicReference<F> failure,
                                    R storedResult, F storedFailure) {
        boolean nothingProducedHere = result.get() == null && failure.get() == null;
        if (result.get() == null && storedResult != null) {
            result.set(storedResult);
        }
        if (nothingProducedHere && storedFailure != null) {
            failure.set(storedFailure);
        }
    }
}
