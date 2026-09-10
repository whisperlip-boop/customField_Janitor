package com.bskim.jira.janitor.fields.scan;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.bskim.jira.janitor.fields.dao.CustomFieldRow;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.dao.ValueCount;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.model.ScanResult;
import com.bskim.jira.janitor.fields.store.SnapshotCodec;
import com.bskim.jira.janitor.fields.store.SnapshotStore;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 스캔 실행기 겸 결과 보관소. 기획서 9장.
 *
 * <p>스캔은 관리자가 "지금 스캔"을 눌렀을 때만 도는 비동기 작업이다. 실시간 조회는
 * 하지 않는다 — 관리자 도구는 예측 가능한 게 실시간보다 낫다.
 *
 * <p>싱글턴이다. 결과를 메모리에 들고 있어야 목록/상세/CSV/커스텀 필드 화면 주입이
 * 모두 같은 스냅샷을 보게 된다. Data Center 다중 노드에서는 노드별로 캐시가 갈리므로
 * 화면에 항상 스캔 시각을 함께 보여준다(9장).
 *
 * <p>Spring 컴포넌트로 등록하지 않고 정적 싱글턴으로 두는 이유: OSGi/Spring 주입
 * 경로를 하나 줄이면 그만큼 조용히 깨질 곳이 줄어든다. 보관 위치가 "플러그인
 * 클래스로더 수명 동안의 메모리"라는 성질은 어느 쪽이든 같다.
 */
public final class ScanService {

    private static final Logger log = Logger.getLogger(ScanService.class);

    private static final ScanService INSTANCE = new ScanService();

    /** 청소 대상이 위로 오는 기본 정렬(기획서 6.1). 같은 상태면 이름, 그다음 ID. */
    private static final Comparator<FieldUsage> CLEANUP_FIRST = new Comparator<FieldUsage>() {
        @Override
        public int compare(FieldUsage left, FieldUsage right) {
            int byStatus = left.getStatus().getCleanupOrder() - right.getStatus().getCleanupOrder();
            if (byStatus != 0) {
                return byStatus;
            }
            int byName = String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName());
            if (byName != 0) {
                return byName;
            }
            return Long.compare(left.getNumericId(), right.getNumericId());
        }
    };

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScanProgress> progress = new AtomicReference<ScanProgress>(ScanProgress.idle());
    private final AtomicReference<ScanResult> lastResult = new AtomicReference<ScanResult>();
    /**
     * 마지막 실패. 진행률({@link ScanProgress})은 다음 스캔이 시작되면 덮이고 화면을
     * 새로 그리면 사라지므로, 실패 사실은 따로 붙잡아 둔다.
     *
     * <p>이게 없으면 두 번째 이후의 스캔이 실패했을 때 관리자가 <b>몇 주 전 스냅샷을
     * 방금 스캔한 결과로 믿는다</b> — 화면에는 이전 스캔 시각만 있고 실패 흔적이 없다.
     */
    private final AtomicReference<ScanFailure> lastFailure = new AtomicReference<ScanFailure>();

    /**
     * 스냅샷을 아직 읽어 보지 않았는가. 플러그인이 켜질 때가 아니라 <b>처음 물어볼 때</b>
     * 읽는다 — 활성화 시점에는 SAL 서비스가 아직 안 떠 있을 수 있다.
     */
    private boolean snapshotRead = false;

    private ScanService() {
    }

    /**
     * 저장된 스냅샷을 메모리로 올린다. 한 번만 한다.
     *
     * <p>복원 사실을 WARN 으로 남기는 이유: 이 인스턴스들은 플러그인 패키지의 INFO 를
     * 버린다(실측). 표에 찍힌 시각이 <b>이번 기동에서 스캔한 것</b>인지 <b>복원된
     * 것</b>인지는 문제를 쫓을 때 첫 번째로 알아야 하는 사실이라 로그에서 사라지면 안 된다.
     *
     * <p>메모리는 DB 행의 캐시다 — 없으면 읽어 오고(read-through), 스캔이 끝나면
     * 쓴다(write-through). Data Center 에서 스캔하지 않은 노드는 자기가 마지막으로
     * 읽은 스냅샷을 계속 보여준다. v1.5 는 노드 간 무효화를 하지 않는다.
     */
    private synchronized void restoreSnapshot() {
        if (snapshotRead) {
            return;
        }
        snapshotRead = true;
        String version = pluginVersion();
        if (UNKNOWN_VERSION.equals(version)) {
            // 버전을 못 읽으면 판 검사가 무력해진다 — 쓸 때도 읽을 때도 "unknown"이라
            // 어느 버전의 스냅샷이든 통과한다. 수집기가 늘어난 뒤 옛 스냅샷을 읽으면
            // 그 참조가 빠진 표가 그려지고 라벨이 뒤집힌다. 시끄럽게 죽는 편이 낫다.
            log.warn("플러그인 버전을 못 읽어 스냅샷을 쓰지 않는다 — 스캔을 눌러야 결과가 나온다");
            return;
        }
        try {
            String raw = SnapshotStore.load();
            SnapshotCodec.Snapshot snapshot = SnapshotCodec.read(raw, version);
            if (snapshot == null) {
                if (raw != null && !raw.trim().isEmpty()) {
                    // 표가 사라진 이유를 남긴다. 이것 없이 업그레이드하면 관리자에게는
                    // "결과가 그냥 없어졌다"로 보인다.
                    log.warn("저장된 스냅샷의 판이 지금 버전(" + version
                            + ")과 달라 버렸다 — 스캔을 다시 눌러야 한다");
                }
                return;
            }
            if (lastResult.get() == null && snapshot.result != null) {
                lastResult.set(snapshot.result);
            }
            if (lastFailure.get() == null && snapshot.failure != null) {
                lastFailure.set(snapshot.failure);
            }
            log.warn("스캔 스냅샷을 복원했다: 필드 "
                    + (snapshot.result == null ? 0 : snapshot.result.getFields().size())
                    + "개, 저장 시각 " + snapshot.savedAt
                    + (snapshot.failure == null ? "" : " (마지막 스캔은 실패였다)"));
        } catch (Throwable t) {
            // 스냅샷은 부가 기능이다. 못 읽으면 "아직 스캔한 적 없음"으로 둔다.
            log.warn("스캔 스냅샷을 읽지 못했다 — 스캔을 다시 눌러야 한다", t);
        }
    }

    /** 결과와 실패를 함께 저장한다. 결과만 남기면 재기동 뒤 실패 배너가 사라진다. */
    private void saveSnapshot() {
        try {
            SnapshotStore.save(SnapshotCodec.write(lastResult.get(), lastFailure.get(), pluginVersion()));
        } catch (Throwable t) {
            log.warn("스캔 스냅샷을 저장하지 못했다 — 다음 재기동에서 결과가 사라진다", t);
        }
    }

    /**
     * 실행 중인 플러그인 버전. 스냅샷의 판을 가르는 값이다.
     *
     * <p>버전이 다르면 스냅샷을 버린다: 수집기가 늘어난 새 버전이 옛 스냅샷을 읽으면
     * 그 참조가 통째로 빠진 채 표가 그려지고, 컬럼으로만 쓰이던 필드가 [미사용]으로
     * 나온다. 정보가 없는 것이 삭제 신호로 바뀌는 것이 이 도구 최악의 실패다.
     */
    /** 버전을 못 읽었을 때의 값. 이 값이면 스냅샷을 쓰지 않는다. */
    private static final String UNKNOWN_VERSION = "unknown";

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
        return UNKNOWN_VERSION;
    }

    public static ScanService getInstance() {
        return INSTANCE;
    }

    /**
     * 스캔을 시작한다. 이미 돌고 있으면 아무것도 하지 않고 false를 준다(기획서 9장의
     * 중복 실행 방지). 호출부는 false를 받으면 현재 진행률을 그대로 돌려주면 된다.
     */
    public boolean startScan() {
        // 저장된 스냅샷을 반드시 <b>쓰기 전에</b> 읽는다. 순서가 뒤집히면 이렇게 된다:
        // 재기동 직후 첫 스캔이 실패하면 lastResult 가 아직 null 인 채로 스냅샷을
        // 저장해 이전의 성공 결과를 지워 버린다 — "아래 표는 이전 스캔의 결과입니다"
        // 배너가 가리킬 표가 없어진다. 실패 경로에서만 드러나는 종류의 유실이다.
        restoreSnapshot();
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        final Date startedAt = new Date();
        progress.set(new ScanProgress(ScanProgress.State.RUNNING, ScanProgress.Stage.FIELDS, startedAt, null));

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ScanResult result = scan(startedAt);
                    lastResult.set(result);
                    lastFailure.set(null);
                    saveSnapshot();
                    progress.set(new ScanProgress(ScanProgress.State.DONE, ScanProgress.Stage.FINISHING,
                            startedAt, null));
                } catch (Throwable e) {
                    // RuntimeException이 아니라 Throwable을 잡는다. 이 플러그인은 8.13으로
                    // 컴파일해 8.17.1에서 돌리므로, 그 가정이 깨질 때 나오는 예외가
                    // NoSuchMethodError / NoClassDefFoundError / AbstractMethodError —
                    // 전부 Error다. 가장 현실적인 실패 모드를 놓치면 진행률이 RUNNING에
                    // 박히고 화면이 1.5초마다 영구 폴링한다(관리자는 이유를 못 본다).
                    log.error("커스텀 필드 사용처 스캔이 실패했다", e);
                    String message = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    lastFailure.set(new ScanFailure(startedAt, new Date(), message));
                    // 실패도 저장한다. 결과만 남기면 재기동 뒤 옛 표가 배너 없이 살아난다.
                    saveSnapshot();
                    progress.set(new ScanProgress(ScanProgress.State.FAILED, null, startedAt, message));
                } finally {
                    running.set(false);
                }
            }
        }, "custom-field-janitor-scan");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public ScanProgress getProgress() {
        return progress.get();
    }

    /** 아직 한 번도 스캔하지 않았으면 null. 화면은 "스캔하세요" 안내를 낸다. */
    public ScanResult getLastResult() {
        restoreSnapshot();
        return lastResult.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 마지막 스캔이 실패했으면 그 기록, 아니면 null. 성공하면 지워진다. */
    public ScanFailure getLastFailure() {
        restoreSnapshot();
        return lastFailure.get();
    }

    private ScanResult scan(Date startedAt) {
        JanitorDao dao = new JanitorDao();

        // 1단계: 필드 목록. 여기서 만든 FieldUsage 객체에 이후 단계가 정보를 붙인다.
        stage(startedAt, ScanProgress.Stage.FIELDS);
        List<ScanProblem> earlyProblems = new ArrayList<ScanProblem>();
        List<FieldUsage> fields = loadFields(dao, earlyProblems);
        // customfield 테이블을 못 읽었으면 판정 대상 자체가 빠져 있다. 화면 맨 위에
        // 알려야 하므로 결과에 플래그로 싣는다("확인 불가" 한 줄로는 안 보인다).
        boolean fieldListDegraded = false;
        for (ScanProblem problem : earlyProblems) {
            if ("customfield".equals(problem.getTarget())) {
                fieldListDegraded = true;
            }
        }
        ScanContext context = new ScanContext(fields);
        context.getProblems().addAll(earlyProblems);

        // 2단계: 값 집계. 축 A. 단일 GROUP BY 두 번(값 / 마지막 변경일)이 전부다.
        stage(startedAt, ScanProgress.Stage.VALUES);
        applyValueCounts(context, dao);

        // 3단계 이후: 설정 참조 수집. 보조 수집기는 실패해도 나머지를 계속한다.
        // 필수 수집기(isEssential)는 실패가 라벨을 뒤집으므로 스캔을 실패시킨다.
        for (ReferenceCollector collector : Collectors.all()) {
            stage(startedAt, collector.getStage());
            try {
                collector.collect(context);
            } catch (Throwable e) {
                String stageName = collector.getStage().name().toLowerCase(java.util.Locale.ENGLISH);
                if (collector.isEssential()) {
                    throw new ScanFailedException("필수 수집 단계 " + stageName + " 실패", e);
                }
                log.warn("수집 단계 " + collector.getStage() + " 실패", e);
                context.addProblem(stageName, "-", e);
            }
        }

        stage(startedAt, ScanProgress.Stage.FINISHING);
        List<FieldUsage> sorted = new ArrayList<FieldUsage>(fields);
        sorted.sort(CLEANUP_FIRST);
        return new ScanResult(startedAt, new Date(), sorted, context.getProblems(), fieldListDegraded);
    }

    private void stage(Date startedAt, ScanProgress.Stage stage) {
        progress.set(new ScanProgress(ScanProgress.State.RUNNING, stage, startedAt, null));
    }

    /**
     * 필드 목록을 만든다. 존재의 근거는 {@code customfield} 테이블이고,
     * 타입 이름 같은 표시 정보는 {@code CustomFieldManager}에서 보충한다.
     *
     * <p>두 곳을 합치는 이유는 실측 때문이다. {@code CustomFieldManager}는 타입을
     * 제공하는 앱이 살아 있는 필드만 돌려준다 — docker Jira에서 11개 중 5개만 나왔고
     * 빠진 6개는 Jira Software가 제공하는 Epic Link / Sprint / Rank 등이었다.
     * 정리 도구에서 그 필드들이 목록에 없으면 관리자는 없는 필드로 믿는다.
     * ({@link com.bskim.jira.janitor.fields.dao.CustomFieldRow} 주석 참고)
     *
     * <p>DB 조회가 실패하면 매니저 목록만으로 진행하고 "확인 불가"에 남긴다 —
     * 반쪽 목록이라도 없는 것보다 낫지만, 반쪽인 사실은 알려야 한다.
     */
    private List<FieldUsage> loadFields(JanitorDao dao, List<ScanProblem> problems) {
        CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();

        Map<Long, CustomField> byId = new LinkedHashMap<Long, CustomField>();
        for (CustomField customField : customFieldManager.getCustomFieldObjects()) {
            if (customField.getIdAsLong() != null) {
                byId.put(customField.getIdAsLong(), customField);
            }
        }

        List<CustomFieldRow> rows;
        try {
            rows = dao.getCustomFieldRows();
        } catch (Throwable e) {
            log.warn("customfield 테이블을 읽지 못했다 — CustomFieldManager 목록만 쓴다", e);
            problems.add(new ScanProblem("fields", "customfield",
                    "필드 목록을 DB에서 읽지 못했다. 타입 제공 앱이 비활성인 필드는 목록에서 빠질 수 있다: "
                            + e.getClass().getSimpleName()));
            rows = new ArrayList<CustomFieldRow>();
            for (Map.Entry<Long, CustomField> entry : byId.entrySet()) {
                CustomField customField = entry.getValue();
                rows.add(new CustomFieldRow(entry.getKey(), customField.getName(),
                        customField.getCustomFieldType() == null
                                ? null : customField.getCustomFieldType().getKey()));
            }
        }

        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        for (CustomFieldRow row : rows) {
            CustomField customField = byId.get(row.getId());
            String typeKey = row.getTypeKey();
            String typeName = null;
            boolean typeAvailable = false;

            if (customField != null && customField.getCustomFieldType() != null) {
                typeName = customField.getCustomFieldType().getName();
                typeAvailable = true;
                if (typeKey == null) {
                    typeKey = customField.getCustomFieldType().getKey();
                }
            }

            String name = customField != null ? customField.getName() : row.getName();
            FieldUsage field = new FieldUsage(row.getId(), name, typeKey,
                    typeName != null ? typeName : typeKey);
            field.setTypeAvailable(typeAvailable);
            fields.add(field);
        }
        return fields;
    }

    /**
     * 값 집계와 마지막 변경일을 붙인다.
     *
     * <p>세 조회의 실패 처리가 각각 다르다.
     *
     * <ul>
     *   <li>{@code customfieldvalue} — <b>필수</b>. 실패하면 스캔을 실패시킨다.
     *       예전에는 필드마다 "확인 불가" 표시만 세웠는데, 그러면 값이 0으로 남아
     *       {@code judge(0, 0, 0, 0)} 이 <b>[미사용] = 삭제 안전</b>을 내놓는다.
     *       숫자 칸은 "확인 불가"인데 라벨은 초록이고 정렬은 그 필드를 맨 위로
     *       올린다 — 관리자가 가장 먼저 지울 후보로 본다. 정보가 없는 것이
     *       "지워도 된다"는 신호로 바뀌므로 부분 결과를 내면 안 된다.</li>
     *   <li>{@code label} 테이블 — <b>필수</b>. 라벨 타입 필드는 값이 여기에만
     *       있으므로(docs/00-환경실측.md 6번) 실패하면 위와 똑같이 0 → [미사용]이
     *       된다. 영향 범위가 라벨 타입으로 좁을 뿐 방향이 같아서 함께 필수로 둔다.
     *       같은 커넥션·같은 스키마인데 한쪽만 실패했다면 어느 수를 믿을 수 있는지
     *       판단할 근거가 없다.</li>
     *   <li>마지막 변경일 — 보조. 실패하면 비워둔다. 없으면 칸이 비고, 라벨 판정에는
     *       쓰이지 않는다.</li>
     * </ul>
     */
    private void applyValueCounts(ScanContext context, JanitorDao dao) {
        try {
            Map<Long, ValueCount> counts = dao.getValueCounts();
            for (FieldUsage field : context.getFields()) {
                ValueCount count = counts.get(field.getNumericId());
                field.setIssuesWithValue(count == null ? 0L : count.getIssues());
                field.setValueRows(count == null ? 0L : count.getRows());
            }

            // 라벨 타입은 값을 label 테이블에 넣는다. 여기를 안 더하면 값이 잔뜩 있는
            // 라벨 필드가 "0건"으로 나오고, 관리자가 그걸 믿고 지운다.
            // 두 테이블에 값이 함께 있을 수는 없지만, 있어도 안전하게 더한다.
            try {
                Map<Long, ValueCount> labelCounts = dao.getLabelValueCounts();
                for (FieldUsage field : context.getFields()) {
                    ValueCount count = labelCounts.get(field.getNumericId());
                    if (count == null) {
                        continue;
                    }
                    field.setIssuesWithValue(field.getIssuesWithValue() + count.getIssues());
                    field.setValueRows(field.getValueRows() + count.getRows());
                }
            } catch (Throwable e) {
                throw new ScanFailedException("라벨 값 집계 실패 (label)", e);
            }
        } catch (ScanFailedException e) {
            // 안쪽(label)에서 이미 판정한 실패다. 다시 포장하면 원인이 바뀐다.
            throw e;
        } catch (Throwable e) {
            // 부분 결과를 내지 않는다 — 위 주석 참조. 값 0은 [미사용]으로 읽힌다.
            throw new ScanFailedException("값 집계 실패 (customfieldvalue)", e);
        }

        try {
            Map<String, Date> lastChanges = dao.getLastValueChangeByFieldName();
            for (FieldUsage field : context.getFields()) {
                // 이름 중복 필드는 이력이 섞이므로 값을 붙이지 않고 부정확 표시만 한다(함정 5).
                if (field.isDuplicateName()) {
                    field.setLastValueChangeAmbiguous(true);
                    continue;
                }
                field.setLastValueChange(lastChanges.get(
                        field.getName() == null ? "" : field.getName().trim().toLowerCase(java.util.Locale.ENGLISH)));
            }
        } catch (Throwable e) {
            log.warn("마지막 변경일 조회 실패 — 보조 지표라 비워둔다", e);
            context.addProblem("lastChange", "changeitem/changegroup", e);
        }
    }
}
