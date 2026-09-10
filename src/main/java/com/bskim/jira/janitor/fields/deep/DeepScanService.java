package com.bskim.jira.janitor.fields.deep;

import com.bskim.jira.janitor.fields.dao.CustomFieldRow;
import com.bskim.jira.janitor.fields.dao.DeepScanDao;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import com.bskim.jira.janitor.fields.scan.ScanFailure;
import com.bskim.jira.janitor.fields.store.PluginVersion;
import com.bskim.jira.janitor.fields.store.ScanLock;
import com.bskim.jira.janitor.fields.store.SnapshotMerge;
import com.bskim.jira.janitor.fields.store.SnapshotStore;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 심층 스캔(기획서 v2). 앱 테이블({@code AO_*})의 문자열 컬럼에서
 * {@code customfield_<id>} 를 찾는다.
 *
 * <p><b>여기서 나온 것은 참조가 아니라 문자열 일치다.</b> 그 테이블이 무슨 뜻인지,
 * 그 행이 살아 있는 설정인지 이력인지 우리는 모른다. 그래서
 * <ul>
 *   <li>라벨 판정에 넣지 않는다 — 넣는 순간 "어딘가에 ID 문자열이 있다"가
 *       "쓰이고 있다"로 승격되고, 그건 이 기능이 하지 않기로 한 해석이다.</li>
 *   <li>결과도 따로 담는다({@link DeepScanResult}).</li>
 * </ul>
 *
 * <p>일반 스캔과 {@link ScanLock} 하나를 나눠 쓴다. 둘 다 DB를 두드리므로 겹치면 서로를
 * 느리게 한다. 스냅샷 규칙(읽기 전에 쓰지 않는다, 버전을 못 읽으면 쓰지 않는다,
 * 실패도 저장한다)은 {@code ScanService} 와 같다 — 그 규칙이 여기만 빠져 있던 것이
 * 리뷰에서 나온 결함이었다.
 */
public final class DeepScanService {

    private static final Logger log = Logger.getLogger(DeepScanService.class);

    private static final DeepScanService INSTANCE = new DeepScanService();

    private final AtomicReference<DeepScanProgress> progress =
            new AtomicReference<DeepScanProgress>(DeepScanProgress.idle());
    private final AtomicReference<DeepScanResult> lastResult = new AtomicReference<DeepScanResult>();
    private final AtomicReference<ScanFailure> lastFailure = new AtomicReference<ScanFailure>();

    private boolean snapshotRead = false;
    /** 잠기지 않는 두 조건의 WARN 을 한 번만 내기 위한 표시. 1.5초 폴링마다 남기면 로그가 넘친다. */
    private boolean warnedUnknownVersion = false;
    private boolean warnedUnreachable = false;

    private DeepScanService() {
    }

    public static DeepScanService getInstance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return ScanLock.getInstance().isHeldBy(ScanLock.DEEP);
    }

    public DeepScanProgress getProgress() {
        return progress.get();
    }

    public DeepScanResult getLastResult() {
        restoreSnapshot();
        return lastResult.get();
    }

    /** 마지막 심층 스캔이 실패했으면 그 기록. 성공하면 지워진다. */
    public ScanFailure getLastFailure() {
        restoreSnapshot();
        return lastFailure.get();
    }

    /**
     * 시작한다.
     *
     * @return 이미 돌고 있거나 <b>일반 스캔이 돌고 있으면</b> false. 화면은 409를 준다.
     */
    public boolean start() {
        restoreSnapshot();
        if (!ScanLock.getInstance().tryAcquire(ScanLock.DEEP)) {
            return false;
        }
        final Date startedAt = new Date();
        progress.set(new DeepScanProgress(DeepScanProgress.State.RUNNING, 0, 0, null, startedAt, null));

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DeepScanResult result = scan(startedAt);
                    lastResult.set(result);
                    lastFailure.set(null);
                    saveSnapshot();
                    progress.set(new DeepScanProgress(DeepScanProgress.State.DONE,
                            result.getTablesScanned(), result.getTablesScanned(), null, startedAt, null));
                } catch (Throwable e) {
                    // 일반 스캔과 같은 이유로 Throwable 이다(docs/00 17번).
                    log.error("심층 스캔이 실패했다", e);
                    String message = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    lastFailure.set(new ScanFailure(startedAt, new Date(), message));
                    saveSnapshot();
                    progress.set(new DeepScanProgress(DeepScanProgress.State.FAILED, 0, 0, null,
                            startedAt, message));
                } finally {
                    ScanLock.getInstance().release(ScanLock.DEEP);
                }
            }
        }, "custom-field-janitor-deep-scan");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private DeepScanResult scan(Date startedAt) {
        DeepScanDao dao = new DeepScanDao();
        ScanContext context = fieldContext();
        List<ScanProblem> problems = new ArrayList<ScanProblem>();
        Map<Long, List<DeepTableMatch>> matches = new LinkedHashMap<Long, List<DeepTableMatch>>();

        List<DeepTable> tables = dao.listTables();
        long rows = 0;
        int index = 0;
        for (DeepTable table : tables) {
            index++;
            progress.set(new DeepScanProgress(DeepScanProgress.State.RUNNING, index, tables.size(),
                    table.getName(), startedAt, null));

            // 행 수 → 조회, 테이블마다 따로. 못 세면 그 사실을 남긴다 — 0 으로 접으면
            // 합계가 조용히 줄고, 관리자가 보는 비용 안내가 거짓이 된다.
            try {
                rows += dao.countRows(table);
            } catch (Throwable e) {
                problems.add(new ScanProblem("deep", table.getName(), "행 수를 못 셌다: " + describe(e)));
            }

            // 테이블 하나가 실패해도 나머지는 훑는다. Exception 이 아니라 Throwable 이다 —
            // 거대 CLOB 의 OutOfMemoryError 나 드라이버 차이의 NoSuchMethodError 가
            // 테이블별 catch 를 지나쳐 스캔 전체를 죽이면 나머지 200개의 결과를 잃는다.
            try {
                DeepScanDao.Rows found = dao.findRows(table);
                for (String[] row : found.rows) {
                    for (FieldUsage field : context.findReferencedFields(row[1])) {
                        matchOf(matches, field.getNumericId(), table.getName()).add(row[0]);
                    }
                }
                if (found.truncated) {
                    // 조용히 자르면 상세 화면이 "발견되지 않았습니다"를 단정문으로 낸다.
                    problems.add(new ScanProblem("deep", table.getName(),
                            "일치 행이 " + DeepScanDao.ROW_LIMIT + "개를 넘어 뒷부분은 보지 않았다"));
                }
            } catch (Throwable e) {
                problems.add(new ScanProblem("deep", table.getName(), describe(e)));
            }
        }

        return new DeepScanResult(startedAt, new Date(), tables.size(), rows, matches, problems);
    }

    /**
     * 문자열에서 필드를 찾아내는 판. <b>일반 스캔과 같은 확정 매칭</b>을 쓴다
     * ({@code customfield_<id>} 표기만, 숫자 단독은 안 본다 — docs/00 4번).
     *
     * <p>필드 목록은 <b>항상</b> DB에서 읽는다. 전에는 마지막 일반 스캔 결과가 있으면
     * 그걸 썼는데, 그 결과는 몇 주 전 스냅샷일 수 있어 그 뒤에 만든 필드를 심층 스캔이
     * 영영 못 봤다(리뷰 지적). 쿼리 하나이고, 이제부터 훑을 AO 테이블 어느 것보다 싸다.
     */
    private ScanContext fieldContext() {
        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        for (CustomFieldRow row : new JanitorDao().getCustomFieldRows()) {
            fields.add(new FieldUsage(row.getId(), row.getName(), row.getTypeKey(), row.getTypeKey()));
        }
        return new ScanContext(fields);
    }

    private static DeepTableMatch matchOf(Map<Long, List<DeepTableMatch>> matches, long fieldId, String table) {
        List<DeepTableMatch> forField = matches.get(fieldId);
        if (forField == null) {
            forField = new ArrayList<DeepTableMatch>();
            matches.put(fieldId, forField);
        }
        // 행은 테이블 순서로 오므로 이 필드의 마지막 항목이 지금 테이블이면 그것이다.
        if (!forField.isEmpty() && forField.get(forField.size() - 1).getTable().equals(table)) {
            return forField.get(forField.size() - 1);
        }
        DeepTableMatch match = new DeepTableMatch(table);
        forField.add(match);
        return match;
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    /** 규칙은 {@code ScanService.restoreSnapshot()} 과 같다. 주석도 그쪽에 있다. */
    private synchronized boolean restoreSnapshot() {
        if (snapshotRead) {
            return true;
        }
        String version = PluginVersion.current();
        if (!PluginVersion.isKnown(version)) {
            if (!warnedUnknownVersion) {
                warnedUnknownVersion = true;
                log.warn("플러그인 버전을 못 읽어 심층 스냅샷을 쓰지 않는다");
            }
            return false;
        }
        SnapshotStore.Loaded loaded = new SnapshotStore(SnapshotStore.DEEP_KEY).load();
        if (!loaded.reachable) {
            if (!warnedUnreachable) {
                warnedUnreachable = true;
                log.warn("심층 스냅샷 저장소에 아직 닿지 못했다 — 다음 요청에서 다시 읽는다");
            }
            return false;
        }
        snapshotRead = true;
        if (loaded.isEmpty()) {
            return true;
        }
        try {
            DeepSnapshotCodec.Snapshot restored = DeepSnapshotCodec.read(loaded.json, version);
            if (restored == null) {
                log.warn("저장된 심층 스캔 스냅샷을 버렸다(판 불일치) — 다시 돌려야 한다");
                return true;
            }
            SnapshotMerge.apply(lastResult, lastFailure, restored.result, restored.failure);
            log.warn("심층 스캔 스냅샷을 복원했다: 필드 "
                    + (restored.result == null ? 0 : restored.result.getFieldCount())
                    + "개" + (restored.failure == null ? "" : " (마지막 스캔은 실패였다)"));
        } catch (Throwable t) {
            log.warn("심층 스캔 스냅샷을 해석하지 못했다", t);
        }
        return true;
    }

    private void saveSnapshot() {
        if (!restoreSnapshot()) {
            log.warn("심층 스냅샷을 아직 읽지 못해 이번 결과를 저장하지 않는다");
            return;
        }
        try {
            new SnapshotStore(SnapshotStore.DEEP_KEY)
                    .save(DeepSnapshotCodec.write(lastResult.get(), lastFailure.get(), PluginVersion.current()));
        } catch (Throwable t) {
            log.warn("심층 스캔 스냅샷을 저장하지 못했다", t);
        }
    }
}
