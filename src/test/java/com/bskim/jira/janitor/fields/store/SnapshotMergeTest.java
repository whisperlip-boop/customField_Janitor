package com.bskim.jira.janitor.fields.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class SnapshotMergeTest {

    @Test
    public void 재시작_직후에는_결과와_실패를_모두_복원한다() {
        AtomicReference<String> result = new AtomicReference<String>();
        AtomicReference<String> failure = new AtomicReference<String>();
        SnapshotMerge.apply(result, failure, "옛 결과", "옛 실패");
        assertEquals("옛 결과", result.get());
        assertEquals("옛 실패", failure.get());
    }

    @Test
    public void 방금_성공한_결과_위에_옛_실패를_올리지_않는다() {
        AtomicReference<String> result = new AtomicReference<String>("방금 성공");
        AtomicReference<String> failure = new AtomicReference<String>();
        SnapshotMerge.apply(result, failure, "옛 결과", "옛 실패");
        assertEquals("방금 성공", result.get());
        assertNull(failure.get());
    }

    @Test
    public void 방금_실패했으면_배너가_가리킬_옛_결과는_복원한다() {
        AtomicReference<String> result = new AtomicReference<String>();
        AtomicReference<String> failure = new AtomicReference<String>("방금 실패");
        SnapshotMerge.apply(result, failure, "옛 결과", "옛 실패");
        assertEquals("옛 결과", result.get());
        assertEquals("방금 실패", failure.get());
    }

    @Test
    public void 저장된_것이_비어_있으면_아무것도_바꾸지_않는다() {
        AtomicReference<String> result = new AtomicReference<String>();
        AtomicReference<String> failure = new AtomicReference<String>();
        SnapshotMerge.apply(result, failure, null, null);
        assertNull(result.get());
        assertNull(failure.get());
    }
}
