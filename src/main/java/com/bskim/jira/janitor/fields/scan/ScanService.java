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
import com.bskim.jira.janitor.fields.store.PluginVersion;
import com.bskim.jira.janitor.fields.store.ScanLock;
import com.bskim.jira.janitor.fields.store.ScanResultCodec;
import com.bskim.jira.janitor.fields.store.SnapshotStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일반 스캔 — 기획서 9장. 생명주기(잠금·스냅샷·실패 보관)는 {@link AbstractScanRunner} 에 있고
 * 여기는 <b>무엇을 훑는지</b>만 있다.
 *
 * <p>스캔은 관리자가 "지금 스캔"을 눌렀을 때만 도는 비동기 작업이다. 실시간 조회는
 * 하지 않는다 — 관리자 도구는 예측 가능한 게 실시간보다 낫다.
 *
 * <p>싱글턴이다. 결과를 메모리에 들고 있어야 목록/상세/CSV/커스텀 필드 화면 주입이
 * 모두 같은 스냅샷을 보게 된다. Spring 컴포넌트로 등록하지 않고 정적 싱글턴으로 두는
 * 이유: OSGi/Spring 주입 경로를 하나 줄이면 그만큼 조용히 깨질 곳이 줄어든다.
 */
public final class ScanService extends AbstractScanRunner<ScanResult, ScanProgress> {

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

    private ScanService() {
        super(ScanLock.SCAN, "custom-field-janitor-scan", "스캔",
                new SnapshotStore(SnapshotStore.SCAN_KEY), ScanResultCodec.INSTANCE, PluginVersion.SOURCE);
    }

    public static ScanService getInstance() {
        return INSTANCE;
    }

    @Override
    protected ScanProgress idleProgress() {
        return ScanProgress.idle();
    }

    @Override
    protected ScanProgress runningProgress(Date startedAt) {
        return new ScanProgress(ScanProgress.State.RUNNING, ScanProgress.Stage.FIELDS, startedAt, null);
    }

    @Override
    protected ScanProgress doneProgress(ScanResult result, Date startedAt) {
        return new ScanProgress(ScanProgress.State.DONE, ScanProgress.Stage.FINISHING, startedAt, null);
    }

    @Override
    protected ScanProgress failedProgress(Date startedAt, String message) {
        return new ScanProgress(ScanProgress.State.FAILED, null, startedAt, message);
    }

    @Override
    protected String describeRestored(ScanResult result) {
        return "필드 " + result.getFields().size() + "개";
    }

    @Override
    protected ScanResult scan(Date startedAt) {
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
        setProgress(new ScanProgress(ScanProgress.State.RUNNING, stage, startedAt, null));
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
