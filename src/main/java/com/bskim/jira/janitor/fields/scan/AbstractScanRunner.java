package com.bskim.jira.janitor.fields.scan;

import com.bskim.jira.janitor.fields.store.PluginVersion;
import com.bskim.jira.janitor.fields.store.ResultCodec;
import com.bskim.jira.janitor.fields.store.ScanLock;
import com.bskim.jira.janitor.fields.store.SnapshotBackend;
import com.bskim.jira.janitor.fields.store.SnapshotEnvelope;
import com.bskim.jira.janitor.fields.store.SnapshotMerge;
import com.bskim.jira.janitor.fields.store.SnapshotStore;
import com.bskim.jira.janitor.fields.store.VersionSource;
import org.apache.log4j.Logger;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 스캔 한 종류의 생명주기: 잠금 → 워커 → 결과/실패 보관 → 스냅샷 저장·복원.
 * 일반 스캔({@code ScanService})과 심층 스캔({@code DeepScanService})이 이걸 상속하고
 * <b>무엇을 훑는지</b>({@link #scan})와 진행률 모양만 채운다.
 *
 * <p>한 곳에 둔 이유: 같은 생명주기 결함이 두 서비스에서 세 번 나왔다(docs/00 34·37·39번 —
 * 읽기 전에 쓰기, 실패 미저장, unknown 버전 가드 누락). 규칙이 두 벌이면 한쪽만 고쳐진다.
 *
 * <p>규칙(전부 실측에서 나온 것):
 * <ul>
 *   <li><b>읽기 전에 쓰지 않는다.</b> {@link #start()} 첫 줄에서 복원한다(34번).</li>
 *   <li><b>저장소에 못 닿은 읽기로는 잠그지 않는다.</b> 잠그면 그 뒤 첫 실패가 좋은 스냅샷을 덮는다(39번).</li>
 *   <li><b>플러그인 버전을 못 읽으면 읽지도 쓰지도 않는다.</b> 판 검사가 무력해진다(34번).</li>
 *   <li><b>실패도 저장한다.</b> 결과만 남기면 재기동 뒤 옛 표가 배너 없이 살아난다(33·37번).</li>
 *   <li><b>실패 복원은 이 JVM 이 아무것도 만들지 않았을 때만.</b> {@link SnapshotMerge}(39번).</li>
 *   <li><b>워커는 {@code Throwable} 을 잡는다.</b> 8.13 컴파일·8.17.1 실행의 실패 모드는 {@code Error} 다(17번).</li>
 * </ul>
 *
 * @param <R> 결과
 * @param <P> 진행률
 */
public abstract class AbstractScanRunner<R, P> {

    protected final Logger log = Logger.getLogger(getClass());

    private final String lockKind;
    private final String threadName;
    /** 로그 문구용 이름("스캔" / "심층 스캔"). */
    private final String label;
    private final SnapshotBackend store;
    private final ResultCodec<R> codec;
    private final VersionSource version;

    private final AtomicReference<P> progress;
    private final AtomicReference<R> lastResult = new AtomicReference<R>();
    /**
     * 마지막 실패. 진행률은 다음 스캔이 시작되면 덮이고 화면을 새로 그리면 사라지므로,
     * 실패 사실은 따로 붙잡아 둔다. 이게 없으면 관리자가 몇 주 전 스냅샷을 방금 것으로 믿는다.
     */
    private final AtomicReference<ScanFailure> lastFailure = new AtomicReference<ScanFailure>();

    /** 스냅샷을 <b>성공적으로</b> 읽었는가. 저장소에 못 닿은 읽기로는 참이 되지 않는다. */
    private boolean snapshotRead = false;
    /** 잠기지 않는 두 조건의 WARN 을 한 번만 내기 위한 표시. 1.5초 폴링마다 남기면 로그가 넘친다. */
    private boolean warnedUnknownVersion = false;
    private boolean warnedUnreachable = false;

    protected AbstractScanRunner(String lockKind, String threadName, String label,
                                 SnapshotBackend store, ResultCodec<R> codec, VersionSource version) {
        this.lockKind = lockKind;
        this.threadName = threadName;
        this.label = label;
        this.store = store;
        this.codec = codec;
        this.version = version;
        this.progress = new AtomicReference<P>(idleProgress());
    }

    // ---- 하위가 채우는 것 -------------------------------------------------------------

    /** 실제 작업. 워커 스레드에서 돈다. 무엇을 던져도 실패로 기록된다. */
    protected abstract R scan(Date startedAt);

    protected abstract P idleProgress();

    protected abstract P runningProgress(Date startedAt);

    protected abstract P doneProgress(R result, Date startedAt);

    protected abstract P failedProgress(Date startedAt, String message);

    /** 복원 WARN 한 줄에 들어갈 요약("필드 24개"). */
    protected abstract String describeRestored(R result);

    // ---- 공개 -----------------------------------------------------------------------

    /**
     * 시작한다. 이미 돌고 있거나 <b>다른 종류의 스캔이 돌고 있으면</b> false — 호출부는 409 를
     * 주고, 무엇 때문에 막혔는지는 {@link ScanLock#holder()} 에 있다.
     */
    public final boolean start() {
        // 저장된 스냅샷을 반드시 쓰기 전에 읽는다. 뒤집히면 재기동 직후 첫 스캔이 실패할 때
        // lastResult 가 null 인 채로 저장해 이전의 성공 결과를 지운다(34번).
        restoreSnapshot();
        // 두 스캔이 하나의 잠금을 나눠 쓴다. 상대 플래그를 보고 자기 플래그를 잡는 방식은 경주가 있었다(39번).
        if (!ScanLock.getInstance().tryAcquire(lockKind)) {
            return false;
        }
        final Date startedAt = new Date();
        progress.set(runningProgress(startedAt));

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    R result = scan(startedAt);
                    lastResult.set(result);
                    lastFailure.set(null);
                    saveSnapshot();
                    progress.set(doneProgress(result, startedAt));
                } catch (Throwable e) {
                    log.error(label + "이 실패했다", e);
                    String message = describe(e);
                    lastFailure.set(new ScanFailure(startedAt, new Date(), message));
                    saveSnapshot();
                    progress.set(failedProgress(startedAt, message));
                } finally {
                    ScanLock.getInstance().release(lockKind);
                }
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public final P getProgress() {
        return progress.get();
    }

    /** 아직 한 번도 돌지 않았으면 null. 화면은 "스캔하세요" 안내를 낸다. */
    public final R getLastResult() {
        restoreSnapshot();
        return lastResult.get();
    }

    /** 마지막 실행이 실패했으면 그 기록, 아니면 null. 성공하면 지워진다. */
    public final ScanFailure getLastFailure() {
        restoreSnapshot();
        return lastFailure.get();
    }

    public final boolean isRunning() {
        return ScanLock.getInstance().isHeldBy(lockKind);
    }

    // ---- 공용 -----------------------------------------------------------------------

    protected final void setProgress(P value) {
        progress.set(value);
    }

    protected static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    // ---- 스냅샷 ---------------------------------------------------------------------

    /**
     * 저장된 스냅샷을 메모리로 올린다. 성공하면 한 번만, 저장소에 못 닿았으면 다음에 다시.
     * 메모리는 DB 행의 캐시다(read-through / write-through). Data Center 에서 스캔하지 않은
     * 노드는 자기가 마지막으로 읽은 스냅샷을 계속 보여준다 — 노드 간 무효화는 하지 않는다.
     *
     * <p>복원·버림 사실은 WARN 으로 남긴다. 이 인스턴스들은 우리 패키지의 INFO 를 버리는데,
     * 표에 찍힌 시각이 이번 기동의 것인지 복원된 것인지는 문제를 쫓을 때 첫 번째로 알아야 한다.
     *
     * @return 이제 스냅샷을 읽은 상태인가. 거짓이면 쓰면 안 된다.
     */
    private synchronized boolean restoreSnapshot() {
        if (snapshotRead) {
            return true;
        }
        String current = version.current();
        if (!PluginVersion.isKnown(current)) {
            if (!warnedUnknownVersion) {
                warnedUnknownVersion = true;
                log.warn("플러그인 버전을 못 읽어 " + label + " 스냅샷을 쓰지 않는다 — 스캔을 눌러야 결과가 나온다");
            }
            return false;
        }
        SnapshotStore.Loaded loaded = store.load();
        if (!loaded.reachable) {
            if (!warnedUnreachable) {
                warnedUnreachable = true;
                log.warn(label + " 스냅샷 저장소에 아직 닿지 못했다 — 다음 요청에서 다시 읽는다");
            }
            return false;
        }
        snapshotRead = true;
        if (loaded.isEmpty()) {
            return true;
        }
        try {
            SnapshotEnvelope.Snapshot<R> snapshot = SnapshotEnvelope.read(codec, loaded.json, current);
            if (snapshot == null) {
                return true;
            }
            if (snapshot.isRejected()) {
                // 표가 사라진 이유를 남긴다. 이것 없이 업그레이드하면 "결과가 그냥 없어졌다"로 보인다.
                log.warn("저장된 " + label + " 스냅샷을 버렸다(" + snapshot.rejected + ") — 스캔을 다시 눌러야 한다");
                return true;
            }
            SnapshotMerge.apply(lastResult, lastFailure, snapshot.result, snapshot.failure);
            log.warn(label + " 스냅샷을 복원했다: "
                    + (snapshot.result == null ? "결과 없음" : describeRestored(snapshot.result))
                    + ", 저장 시각 " + snapshot.savedAt
                    + (snapshot.failure == null ? "" : " (마지막 스캔은 실패였다)"));
        } catch (Throwable t) {
            // 형식이 깨진 스냅샷. 읽기는 성공했으므로 잠근다 — 다음 스캔이 덮어쓴다.
            log.warn(label + " 스냅샷을 해석하지 못했다 — 스캔을 다시 눌러야 한다", t);
        }
        return true;
    }

    /**
     * 결과와 실패를 함께 저장한다. <b>읽지 않은 저장소에는 쓰지 않는다</b> — 지금 메모리는
     * DB 보다 적게 알고 있을 수 있고, 그걸 쓰면 좋은 스냅샷을 덮는다. 다음 스캔이 다시 시도한다.
     */
    private void saveSnapshot() {
        if (!restoreSnapshot()) {
            log.warn(label + " 스냅샷을 아직 읽지 못해 이번 결과를 저장하지 않는다 — 재기동하면 사라진다");
            return;
        }
        try {
            store.save(SnapshotEnvelope.write(codec, lastResult.get(), lastFailure.get(), version.current()));
        } catch (Throwable t) {
            log.warn(label + " 스냅샷을 저장하지 못했다 — 다음 재기동에서 결과가 사라진다", t);
        }
    }
}
