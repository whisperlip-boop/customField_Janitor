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

    private ScanService() {
    }

    public static ScanService getInstance() {
        return INSTANCE;
    }

    /**
     * 스캔을 시작한다. 이미 돌고 있으면 아무것도 하지 않고 false를 준다(기획서 9장의
     * 중복 실행 방지). 호출부는 false를 받으면 현재 진행률을 그대로 돌려주면 된다.
     */
    public boolean startScan() {
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
                    progress.set(new ScanProgress(ScanProgress.State.DONE, ScanProgress.Stage.FINISHING,
                            startedAt, null));
                } catch (RuntimeException e) {
                    log.error("커스텀 필드 사용처 스캔이 실패했다", e);
                    progress.set(new ScanProgress(ScanProgress.State.FAILED, null, startedAt,
                            e.getClass().getSimpleName() + ": " + e.getMessage()));
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
        return lastResult.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    private ScanResult scan(Date startedAt) {
        JanitorDao dao = new JanitorDao();

        // 1단계: 필드 목록. 여기서 만든 FieldUsage 객체에 이후 단계가 정보를 붙인다.
        stage(startedAt, ScanProgress.Stage.FIELDS);
        List<ScanProblem> earlyProblems = new ArrayList<ScanProblem>();
        List<FieldUsage> fields = loadFields(dao, earlyProblems);
        ScanContext context = new ScanContext(fields);
        context.getProblems().addAll(earlyProblems);

        // 2단계: 값 집계. 축 A. 단일 GROUP BY 두 번(값 / 마지막 변경일)이 전부다.
        stage(startedAt, ScanProgress.Stage.VALUES);
        applyValueCounts(context, dao);

        // 3단계 이후: 설정 참조 수집. 한 수집기가 실패해도 나머지는 계속한다.
        for (ReferenceCollector collector : Collectors.all()) {
            stage(startedAt, collector.getStage());
            try {
                collector.collect(context);
            } catch (RuntimeException e) {
                log.warn("수집 단계 " + collector.getStage() + " 실패", e);
                context.addProblem(collector.getStage().name().toLowerCase(java.util.Locale.ENGLISH),
                        "-", e);
            }
        }

        stage(startedAt, ScanProgress.Stage.FINISHING);
        List<FieldUsage> sorted = new ArrayList<FieldUsage>(fields);
        sorted.sort(CLEANUP_FIRST);
        return new ScanResult(startedAt, new Date(), sorted, context.getProblems());
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
        } catch (RuntimeException e) {
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
     * <p>둘은 실패 처리가 다르다. 값 집계가 실패하면 상태 판정 자체가 불가능하므로
     * 필드마다 "확인 불가" 표시를 세운다(0으로 오해하면 관리자가 필드를 지운다).
     * 마지막 변경일은 보조 지표라 실패하면 비워둔다.
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
            } catch (RuntimeException e) {
                log.warn("라벨 값 집계 실패 — 라벨 타입 필드가 0건으로 보일 수 있다", e);
                context.addProblem("values", "label", e);
            }
        } catch (RuntimeException e) {
            log.warn("값 집계 실패 — 전 필드를 확인 불가로 표시한다", e);
            context.addProblem("values", "customfieldvalue", e);
            for (FieldUsage field : context.getFields()) {
                field.setValueCountUnavailable(true);
            }
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
        } catch (RuntimeException e) {
            log.warn("마지막 변경일 조회 실패 — 보조 지표라 비워둔다", e);
            context.addProblem("lastChange", "changeitem/changegroup", e);
        }
    }
}
