package com.bskim.jira.janitor.fields.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.store.PluginVersion;
import com.bskim.jira.janitor.fields.store.ResultCodec;
import com.bskim.jira.janitor.fields.store.ScanLock;
import com.bskim.jira.janitor.fields.store.SnapshotBackend;
import com.bskim.jira.janitor.fields.store.SnapshotEnvelope;
import com.bskim.jira.janitor.fields.store.SnapshotStore;
import com.bskim.jira.janitor.fields.store.VersionSource;

import org.junit.After;
import org.junit.Test;

/**
 * 생명주기 규칙을 Jira 없이 고정한다. 세 번 밟은 결함 부류(docs/00 34·37·39번)가 전부 여기 있다.
 * 결과는 문자열 하나, 진행률은 상태 이름 하나다.
 */
public class AbstractScanRunnerTest {

    private static final String LOCK = "test";

    /** 결과가 문자열 하나인 코덱. */
    private static final ResultCodec<String> WORDS = new ResultCodec<String>() {
        @Override public int schema() { return 1; }
        @Override public JSONObject write(String result) throws JSONException { return new JSONObject().put("w", result); }
        @Override public String read(JSONObject json) throws JSONException { return json.getString("w"); }
    };

    /** 닿을지 말지, 무엇이 들어 있을지를 테스트가 정하는 저장소. 저장은 전부 기록한다. */
    private static final class FakeStore implements SnapshotBackend {
        volatile boolean reachable = true;
        volatile String json;
        final List<String> saved = new ArrayList<String>();

        @Override public SnapshotStore.Loaded load() {
            return reachable ? SnapshotStore.Loaded.of(json) : SnapshotStore.Loaded.unreachable();
        }
        @Override public boolean save(String value) {
            saved.add(value);
            json = value;
            return true;
        }
    }

    private static final class FakeVersion implements VersionSource {
        volatile String value = "1.4.0";
        @Override public String current() { return value; }
    }

    private static final class Runner extends AbstractScanRunner<String, String> {
        volatile Callable<String> body;

        Runner(FakeStore store, FakeVersion version) {
            super(LOCK, "runner-test", "시험 스캔", store, WORDS, version);
        }
        @Override protected String scan(Date startedAt) {
            try {
                return body.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        @Override protected String idleProgress() { return "IDLE"; }
        @Override protected String runningProgress(Date startedAt) { return "RUNNING"; }
        @Override protected String doneProgress(String result, Date startedAt) { return "DONE"; }
        @Override protected String failedProgress(Date startedAt, String message) { return "FAILED:" + message; }
        @Override protected String describeRestored(String result) { return result; }
    }

    private final FakeStore store = new FakeStore();
    private final FakeVersion version = new FakeVersion();
    private final Runner runner = new Runner(store, version);

    @After
    public void 잠금을_남기지_않는다() {
        ScanLock.getInstance().release(LOCK);
    }

    private static String envelope(String result, String failureMessage, String pluginVersion) throws Exception {
        ScanFailure failure = failureMessage == null ? null
                : new ScanFailure(new Date(1000), new Date(2000), failureMessage);
        return SnapshotEnvelope.write(WORDS, result, failure, pluginVersion);
    }

    /** 워커가 끝날 때까지(잠금이 풀릴 때까지) 기다린다. 5초를 넘기면 실패다 — 잠금을 쥔 채 멈춘 것이다. */
    private void runAndWait(Callable<String> body) throws Exception {
        runner.body = body;
        assertTrue("시작했어야 한다", runner.start());
        long deadline = System.currentTimeMillis() + 5000;
        while (runner.isRunning()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("워커가 5초 안에 끝나지 않았다");
            }
            Thread.sleep(5);
        }
    }

    private static Callable<String> returning(final String value) {
        return new Callable<String>() {
            @Override public String call() { return value; }
        };
    }

    // ---- 사례 -----------------------------------------------------------------------

    @Test
    public void 시작할_때_못_닿고_저장할_때_닿아도_옛_실패가_되살아나지_않는다() throws Exception {
        store.json = envelope("옛 결과", "옛 실패", "1.4.0");
        store.reachable = false;
        runAndWait(new Callable<String>() {
            @Override public String call() {
                store.reachable = true;   // 스캔 도중 SAL 이 떴다
                return "새 결과";
            }
        });
        assertEquals("새 결과", runner.getLastResult());
        assertNull("방금 성공했는데 옛 실패가 올라왔다", runner.getLastFailure());
        assertEquals("DONE", runner.getProgress());
        assertEquals(1, store.saved.size());
        SnapshotEnvelope.Snapshot<String> written = SnapshotEnvelope.read(WORDS, store.saved.get(0), "1.4.0");
        assertEquals("새 결과", written.result);
        assertNull(written.failure);
    }

    @Test
    public void 실패하면_이전_결과는_남고_실패가_함께_저장된다() throws Exception {
        store.json = envelope("옛 결과", null, "1.4.0");
        runAndWait(new Callable<String>() {
            @Override public String call() { throw new IllegalStateException("boom"); }
        });
        assertEquals("옛 결과", runner.getLastResult());
        assertEquals("IllegalStateException: boom", runner.getLastFailure().getMessage());
        assertEquals("FAILED:IllegalStateException: boom", runner.getProgress());
        SnapshotEnvelope.Snapshot<String> written = SnapshotEnvelope.read(WORDS, store.saved.get(0), "1.4.0");
        assertEquals("옛 결과", written.result);
        assertEquals("IllegalStateException: boom", written.failure.getMessage());
    }

    @Test
    public void 플러그인_버전이_다르면_버리고_새_결과로_덮는다() throws Exception {
        store.json = envelope("옛 결과", "옛 실패", "1.3.2");
        assertNull(runner.getLastResult());
        assertNull(runner.getLastFailure());
        runAndWait(returning("새 결과"));
        assertEquals("새 결과", SnapshotEnvelope.read(WORDS, store.json, "1.4.0").result);
    }

    @Test
    public void 버전을_모르면_읽지도_쓰지도_않는다() throws Exception {
        version.value = PluginVersion.UNKNOWN;
        store.json = envelope("옛 결과", null, PluginVersion.UNKNOWN);
        assertNull("unknown 끼리 맞는다고 복원하면 판 검사가 무력해진다", runner.getLastResult());
        runAndWait(returning("새 결과"));
        assertEquals("새 결과", runner.getLastResult());
        assertTrue("저장했으면 안 된다", store.saved.isEmpty());
    }

    @Test
    public void 저장소에_끝내_못_닿으면_저장을_건너뛴다() throws Exception {
        store.reachable = false;
        runAndWait(returning("새 결과"));
        assertEquals("새 결과", runner.getLastResult());
        assertTrue(store.saved.isEmpty());
    }

    @Test
    public void 재기동_직후에는_결과와_실패를_모두_복원한다() throws Exception {
        store.json = envelope("옛 결과", "옛 실패", "1.4.0");
        assertEquals("옛 결과", runner.getLastResult());
        assertEquals("옛 실패", runner.getLastFailure().getMessage());
    }

    @Test
    public void 돌고_있으면_다시_시작하지_않는다() throws Exception {
        final Object gate = new Object();
        runner.body = new Callable<String>() {
            @Override public String call() throws Exception {
                synchronized (gate) { gate.wait(3000); }
                return "x";
            }
        };
        assertTrue(runner.start());
        assertFalse(runner.start());
        assertEquals(LOCK, ScanLock.getInstance().holder());
        synchronized (gate) { gate.notifyAll(); }
        long deadline = System.currentTimeMillis() + 5000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(runner.isRunning());
        assertNotNull(runner.getLastResult());
    }
}
