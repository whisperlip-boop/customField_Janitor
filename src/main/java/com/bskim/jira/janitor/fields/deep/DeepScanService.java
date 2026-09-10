package com.bskim.jira.janitor.fields.deep;

import com.atlassian.jira.component.ComponentAccessor;
import com.bskim.jira.janitor.fields.dao.DeepScanDao;
import com.bskim.jira.janitor.fields.dao.CustomFieldRow;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import com.bskim.jira.janitor.fields.scan.ScanFailure;
import com.bskim.jira.janitor.fields.scan.ScanService;
import com.bskim.jira.janitor.fields.store.SnapshotStore;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>일반 스캔과 동시에 돌지 않는다. 둘 다 DB를 두드리므로 겹치면 서로를 느리게 한다.
 */
public final class DeepScanService {

    private static final Logger log = Logger.getLogger(DeepScanService.class);

    private static final DeepScanService INSTANCE = new DeepScanService();

    /** 한 필드가 한 테이블에서 가질 수 있는 행 표기 수. 나머지는 건수로만 남는다. */
    static final int HITS_PER_FIELD_TABLE = 20;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<DeepScanProgress> progress =
            new AtomicReference<DeepScanProgress>(DeepScanProgress.idle());
    private final AtomicReference<DeepScanResult> lastResult = new AtomicReference<DeepScanResult>();
    /**
     * 마지막 실패. 진행률은 화면을 새로 그리면 사라지고, 재기동하면 더더욱 사라진다.
     * 그러면 관리자는 <b>실패한 스캔 다음에 살아난 옛 결과</b>를 최신으로 믿는다 —
     * 일반 스캔에서 두 번 고친 것과 같은 모양이다(docs/00 18·34번).
     */
    private final AtomicReference<ScanFailure> lastFailure = new AtomicReference<ScanFailure>();

    private boolean snapshotRead = false;

    private DeepScanService() {
    }

    public static DeepScanService getInstance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running.get();
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
        // 일반 스캔과 마찬가지로 쓰기보다 읽기가 먼저다(docs/00 34번).
        restoreSnapshot();
        if (ScanService.getInstance().isRunning()) {
            return false;
        }
        if (!running.compareAndSet(false, true)) {
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
                    // 실패도 저장한다. 결과만 남기면 재기동 뒤 옛 결과가 흔적 없이 살아난다.
                    saveSnapshot();
                    progress.set(new DeepScanProgress(DeepScanProgress.State.FAILED, 0, 0, null,
                            startedAt, message));
                } finally {
                    running.set(false);
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

        List<DeepTable> tables = dao.listTables();
        long rows = 0;
        for (DeepTable table : tables) {
            rows += Math.max(0, table.getRowCount());
        }

        Map<Long, List<DeepHit>> hits = new LinkedHashMap<Long, List<DeepHit>>();
        int index = 0;
        for (DeepTable table : tables) {
            index++;
            progress.set(new DeepScanProgress(DeepScanProgress.State.RUNNING, index, tables.size(),
                    table.getName(), startedAt, null));
            try {
                for (String[] row : dao.findRows(table)) {
                    for (FieldUsage field : context.findReferencedFields(row[1])) {
                        add(hits, field.getNumericId(), new DeepHit(table.getName(), row[0]));
                    }
                }
            } catch (Exception e) {
                // 테이블 하나가 실패해도 나머지는 훑는다. 앱 하나 때문에 전체를 버리지 않는다.
                problems.add(new ScanProblem("deep", table.getName(),
                        e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())));
            }
        }

        return new DeepScanResult(startedAt, new Date(), tables.size(),
                new ArrayList<String>(DeepScanDao.SKIPPED_PREFIXES), rows, hits, problems);
    }

    /**
     * 문자열에서 필드를 찾아내는 판. <b>일반 스캔과 같은 확정 매칭</b>을 쓴다
     * ({@code customfield_<id>} 표기만, 숫자 단독은 안 본다 — docs/00 4번).
     *
     * <p>필드 목록은 마지막 스캔 결과가 있으면 그걸 쓰고, 없으면 DB에서 읽는다.
     * 심층 스캔만 먼저 돌려도 동작해야 하기 때문이다.
     */
    private ScanContext fieldContext() {
        if (ScanService.getInstance().getLastResult() != null) {
            return new ScanContext(ScanService.getInstance().getLastResult().getFields());
        }
        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        for (CustomFieldRow row : new JanitorDao().getCustomFieldRows()) {
            fields.add(new FieldUsage(row.getId(), row.getName(), row.getTypeKey(), row.getTypeKey()));
        }
        return new ScanContext(fields);
    }

    private static void add(Map<Long, List<DeepHit>> hits, long fieldId, DeepHit hit) {
        List<DeepHit> forField = hits.get(fieldId);
        if (forField == null) {
            forField = new ArrayList<DeepHit>();
            hits.put(fieldId, forField);
        }
        int sameTable = 0;
        for (DeepHit existing : forField) {
            if (existing.getTable().equals(hit.getTable())) {
                sameTable++;
            }
        }
        // 한 테이블이 결과를 뒤덮지 않게 한다. 넘으면 그 행은 버린다 — 관리자가
        // 봐야 하는 것은 "어느 앱 테이블에 있다"이지 500개 행 번호가 아니다.
        if (sameTable < HITS_PER_FIELD_TABLE) {
            forField.add(hit);
        }
    }

    private synchronized void restoreSnapshot() {
        if (snapshotRead) {
            return;
        }
        snapshotRead = true;
        try {
            String raw = new SnapshotStore(SnapshotStore.DEEP_KEY).load();
            DeepSnapshotCodec.Snapshot restored = DeepSnapshotCodec.read(raw, pluginVersion());
            if (restored != null) {
                if (restored.result != null) {
                    lastResult.set(restored.result);
                }
                if (restored.failure != null) {
                    lastFailure.set(restored.failure);
                }
                log.warn("심층 스캔 스냅샷을 복원했다: 필드 "
                        + (restored.result == null ? 0 : restored.result.getFieldCount())
                        + "개" + (restored.failure == null ? "" : " (마지막 스캔은 실패였다)"));
            } else if (raw != null && !raw.trim().isEmpty()) {
                log.warn("저장된 심층 스캔 스냅샷을 버렸다(판 불일치) — 다시 돌려야 한다");
            }
        } catch (Throwable t) {
            log.warn("심층 스캔 스냅샷을 읽지 못했다", t);
        }
    }

    private void saveSnapshot() {
        try {
            new SnapshotStore(SnapshotStore.DEEP_KEY)
                    .save(DeepSnapshotCodec.write(lastResult.get(), lastFailure.get(), pluginVersion()));
        } catch (Throwable t) {
            log.warn("심층 스캔 스냅샷을 저장하지 못했다", t);
        }
    }

    private static String pluginVersion() {
        try {
            com.atlassian.plugin.Plugin plugin = ComponentAccessor.getPluginAccessor()
                    .getPlugin("com.bskim.jira.janitor");
            if (plugin != null && plugin.getPluginInformation() != null) {
                String version = plugin.getPluginInformation().getVersion();
                if (version != null && !version.trim().isEmpty()) {
                    return version;
                }
            }
        } catch (Throwable t) {
            log.debug("플러그인 버전을 못 읽었다", t);
        }
        return "unknown";
    }
}
