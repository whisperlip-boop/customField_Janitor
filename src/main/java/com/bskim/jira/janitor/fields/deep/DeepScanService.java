package com.bskim.jira.janitor.fields.deep;

import com.bskim.jira.janitor.fields.dao.CustomFieldRow;
import com.bskim.jira.janitor.fields.dao.DeepScanDao;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.scan.AbstractScanRunner;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import com.bskim.jira.janitor.fields.store.PluginVersion;
import com.bskim.jira.janitor.fields.store.ScanLock;
import com.bskim.jira.janitor.fields.store.SnapshotStore;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 심층 스캔(기획서 v2). 앱 테이블({@code AO_*})의 문자열 컬럼에서
 * {@code customfield_<id>} 를 찾는다. 생명주기는 {@link AbstractScanRunner} 에 있다.
 *
 * <p><b>여기서 나온 것은 참조가 아니라 문자열 일치다.</b> 그 테이블이 무슨 뜻인지,
 * 그 행이 살아 있는 설정인지 이력인지 우리는 모른다. 그래서
 * <ul>
 *   <li>라벨 판정에 넣지 않는다 — 넣는 순간 "어딘가에 ID 문자열이 있다"가
 *       "쓰이고 있다"로 승격되고, 그건 이 기능이 하지 않기로 한 해석이다.</li>
 *   <li>결과도 따로 담는다({@link DeepScanResult}).</li>
 * </ul>
 */
public final class DeepScanService extends AbstractScanRunner<DeepScanResult, DeepScanProgress> {

    private static final DeepScanService INSTANCE = new DeepScanService();

    private DeepScanService() {
        super(ScanLock.DEEP, "custom-field-janitor-deep-scan", "심층 스캔",
                new SnapshotStore(SnapshotStore.DEEP_KEY), DeepResultCodec.INSTANCE, PluginVersion.SOURCE);
    }

    public static DeepScanService getInstance() {
        return INSTANCE;
    }

    @Override
    protected DeepScanProgress idleProgress() {
        return DeepScanProgress.idle();
    }

    @Override
    protected DeepScanProgress runningProgress(Date startedAt) {
        return new DeepScanProgress(DeepScanProgress.State.RUNNING, 0, 0, null, startedAt, null);
    }

    @Override
    protected DeepScanProgress doneProgress(DeepScanResult result, Date startedAt) {
        return new DeepScanProgress(DeepScanProgress.State.DONE,
                result.getTablesScanned(), result.getTablesScanned(), null, startedAt, null);
    }

    @Override
    protected DeepScanProgress failedProgress(Date startedAt, String message) {
        return new DeepScanProgress(DeepScanProgress.State.FAILED, 0, 0, null, startedAt, message);
    }

    @Override
    protected String describeRestored(DeepScanResult result) {
        return "필드 " + result.getFieldCount() + "개, 테이블 " + result.getTablesScanned() + "개";
    }

    @Override
    protected DeepScanResult scan(Date startedAt) {
        DeepScanDao dao = new DeepScanDao();
        final ScanContext context = fieldContext();
        List<ScanProblem> problems = new ArrayList<ScanProblem>();
        final Map<Long, List<DeepTableMatch>> matches = new LinkedHashMap<Long, List<DeepTableMatch>>();

        List<DeepTable> tables = dao.listTables();
        long rows = 0;
        int index = 0;
        for (final DeepTable table : tables) {
            index++;
            setProgress(new DeepScanProgress(DeepScanProgress.State.RUNNING, index, tables.size(),
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
                DeepScanDao.Rows found = dao.findRows(table, new DeepScanDao.RowSink() {
                    @Override
                    public void row(String rowId, List<String> values) {
                        record(matches, context, table.getName(), rowId, values);
                    }
                });
                if (found.truncated) {
                    // 조용히 자르면 상세 화면이 "발견되지 않았습니다"를 단정문으로 낸다.
                    problems.add(new ScanProblem("deep", table.getName(),
                            "일치 행이 " + DeepScanPolicy.ROW_LIMIT + "개를 넘어 뒷부분은 보지 않았다"));
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

    /**
     * 한 행의 컬럼 값들을 필드 단위로 합쳐 <b>행마다 한 번만</b> 센다. 두 컬럼에 같은 필드가
     * 있어도 1건이다 — 전에 컬럼을 이어붙여 한 문자열로 매칭하던 때와 같은 셈이다.
     * 값마다 따로 매칭하는 이유는 CLOB 을 이어붙이는 복사를 없애기 위해서다(리뷰 #9).
     */
    static void record(Map<Long, List<DeepTableMatch>> matches, ScanContext context, String table,
                       String rowId, List<String> values) {
        Set<FieldUsage> hit = new LinkedHashSet<FieldUsage>();
        for (String value : values) {
            hit.addAll(context.findReferencedFields(value));
        }
        for (FieldUsage field : hit) {
            matchOf(matches, field.getNumericId(), table).add(rowId);
        }
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
}
