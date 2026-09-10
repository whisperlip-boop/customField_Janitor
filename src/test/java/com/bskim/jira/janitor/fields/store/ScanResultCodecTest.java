package com.bskim.jira.janitor.fields.store;

import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.model.ScanResult;
import com.bskim.jira.janitor.fields.model.UsageStatus;
import com.bskim.jira.janitor.fields.scan.ScanFailure;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 스냅샷 왕복. 여기가 틀리면 재기동 후 표가 <b>조용히 달라진다</b> — 스캔은 성공한
 * 것으로 보이는데 라벨만 바뀌어 있다. 그래서 라벨을 정하는 값(값 수·참조·잠김·
 * 중복 이름)을 전부 확인한다.
 */
public class ScanResultCodecTest {

    private static final ScanResultCodec CODEC = ScanResultCodec.INSTANCE;

    private static final String VERSION = "1.1.0";

    private static ScanResult sample() {
        FieldUsage active = new FieldUsage(10001L, "Story Points", "type.number", "Number Field");
        active.setIssuesWithValue(12);
        active.setValueRows(15);
        active.setLastValueChange(new Date(1700000000000L));
        active.setDuplicateName(true);
        active.setLastValueChangeAmbiguous(true);
        active.addContextProject("TOP");
        active.addContextIssueType("Task");
        active.addReference(new Reference(ReferenceType.SCREEN, "Default Screen", "10000",
                "Field Tab", "/secure/admin/ConfigureFieldScreen.jspa?id=10000"));
        active.addReference(new Reference(ReferenceType.COLUMN_LAYOUT, null, "personal",
                "2 · bskim, Janitor Tester", "janitor.fields.ref.columnLayout.personal", null,
                Collections.<String>emptyList(), false));
        active.addReference(new Reference(ReferenceType.PERMISSION_SCHEME, "Default Permission Scheme",
                "0:10", "Browse Projects", "/secure/admin/EditPermissions!default.jspa?schemeId=0",
                Arrays.asList("TOP")));

        FieldUsage dead = new FieldUsage(10002L, "Sprint", "agile.sprint", "agile.sprint");
        dead.setTypeAvailable(false);
        dead.setGlobalContext(true);
        dead.addReference(new Reference(ReferenceType.MANAGED, "Jira Software", "10002",
                "LOCKED", null, null, Collections.<String>emptyList(), true));

        List<FieldUsage> fields = new ArrayList<FieldUsage>(Arrays.asList(active, dead));
        List<ScanProblem> problems = Arrays.asList(ScanProblem.raw("filter", "깨진 필터 (10102)", "JQL 파싱 실패"));
        return new ScanResult(new Date(1700000000000L), new Date(1700000060000L), fields, problems, true);
    }

    private static ScanResult roundTrip(ScanResult result) throws Exception {
        SnapshotEnvelope.Snapshot<ScanResult> back =
                SnapshotEnvelope.read(CODEC, SnapshotEnvelope.write(CODEC, result, null, VERSION), VERSION);
        return back.result;
    }

    @Test
    public void 결과_전체가_왕복한다() throws Exception {
        ScanResult before = sample();
        ScanResult after = roundTrip(before);

        assertEquals(before.getStartedAt(), after.getStartedAt());
        assertEquals(before.getFinishedAt(), after.getFinishedAt());
        assertEquals(before.getFields().size(), after.getFields().size());
        assertEquals(1, after.getProblems().size());
        assertEquals("filter", after.getProblems().get(0).getArea());
    }

    @Test
    public void 목록이_반쪽이라는_사실이_남는다() throws Exception {
        // 이게 빠지면 재기동 후 배너가 사라지고 표가 완전한 것으로 읽힌다.
        assertTrue(roundTrip(sample()).isFieldListDegraded());
    }

    @Test
    public void 라벨을_정하는_값들이_그대로다() throws Exception {
        FieldUsage before = sample().getFields().get(0);
        FieldUsage after = roundTrip(sample()).getField(10001L);

        assertEquals(before.getIssuesWithValue(), after.getIssuesWithValue());
        assertEquals(before.getValueRows(), after.getValueRows());
        assertEquals(before.getLastValueChange(), after.getLastValueChange());
        assertTrue(after.isLastValueChangeAmbiguous());
        assertTrue(after.isDuplicateName());
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getTotalReferenceCount(), after.getTotalReferenceCount());
        assertEquals(before.getEvidenceReferenceCount(), after.getEvidenceReferenceCount());
        assertEquals(before.getRiskyReferenceCount(), after.getRiskyReferenceCount());
    }

    @Test
    public void 잠긴_필드는_복원해도_위험이다() throws Exception {
        // 스냅샷을 거치면서 [위험]이 [미사용]으로 뒤집히는 것이 최악의 회귀다.
        FieldUsage after = roundTrip(sample()).getField(10002L);
        assertEquals(UsageStatus.AT_RISK, after.getStatus());
        assertFalse(after.isTypeAvailable());
        assertTrue(after.isGlobalContext());
    }

    @Test
    public void 참조의_모든_칸이_왕복한다() throws Exception {
        List<Reference> after = roundTrip(sample()).getField(10001L)
                .getReferences(ReferenceType.COLUMN_LAYOUT);
        assertEquals(1, after.size());
        assertNull(after.get(0).getTargetName());
        assertEquals("personal", after.get(0).getTargetId());
        assertEquals("janitor.fields.ref.columnLayout.personal", after.get(0).getDetailI18nKey());
        assertEquals("2 · bskim, Janitor Tester", after.get(0).getDetail());

        Reference scheme = roundTrip(sample()).getField(10001L)
                .getReferences(ReferenceType.PERMISSION_SCHEME).get(0);
        assertEquals(Arrays.asList("TOP"), scheme.getProjects());
        assertTrue(scheme.isRisky());
    }

    @Test
    public void 컨텍스트_목록도_왕복한다() throws Exception {
        FieldUsage after = roundTrip(sample()).getField(10001L);
        assertEquals(Arrays.asList("TOP"), after.getContextProjects());
        assertEquals(Arrays.asList("Task"), after.getContextIssueTypes());
    }

    @Test
    public void 실패도_함께_저장된다() throws Exception {
        // 결과보다 나중에 실패한 스캔. 이 순서라야 "표는 낡았다" 배너가 뜬다.
        ScanFailure failure = new ScanFailure(new Date(1700000100000L), new Date(1700000130000L),
                "DaoException: 값 집계 쿼리 실패");
        SnapshotEnvelope.Snapshot<ScanResult> back = SnapshotEnvelope.read(CODEC, 
                SnapshotEnvelope.write(CODEC, sample(), failure, VERSION), VERSION);

        assertEquals(failure.getStartedAt(), back.failure.getStartedAt());
        assertEquals(failure.getFinishedAt(), back.failure.getFinishedAt());
        assertEquals(failure.getMessage(), back.failure.getMessage());
        // 실패와 결과가 함께 있어야 "표는 낡았다" 배너를 다시 그릴 수 있다.
        assertTrue(back.failure.getFinishedAt().after(back.result.getFinishedAt()));
    }

    @Test
    public void 결과가_없어도_저장된다() throws Exception {
        // 첫 스캔이 실패한 경우. 결과는 없고 실패만 있다.
        ScanFailure failure = new ScanFailure(new Date(1L), new Date(2L), "boom");
        SnapshotEnvelope.Snapshot<ScanResult> back = SnapshotEnvelope.read(CODEC, 
                SnapshotEnvelope.write(CODEC, null, failure, VERSION), VERSION);
        assertNull(back.result);
        assertEquals("boom", back.failure.getMessage());
    }

    @Test
    public void 플러그인_버전이_다르면_버린다() throws Exception {
        // 수집기가 늘어난 새 버전이 옛 스냅샷을 읽으면 그 참조가 통째로 빠진 표가
        // 그려진다 — 없는 정보가 "삭제해도 된다"는 신호로 바뀐다.
        String json = SnapshotEnvelope.write(CODEC, sample(), null, "1.0.2");
        assertTrue(SnapshotEnvelope.read(CODEC, json, "1.1.0").isRejected());
    }

    @Test
    public void 판이_다르거나_비어_있으면_버린다() throws Exception {
        assertNull(SnapshotEnvelope.read(CODEC, null, VERSION));
        assertNull(SnapshotEnvelope.read(CODEC, "   ", VERSION));
        assertTrue(SnapshotEnvelope.read(CODEC, "{\"envelope\":1,\"schema\":999,\"pluginVersion\":\"1.1.0\"}", VERSION).isRejected());
        // 1.3.2 까지의 봉투(envelope 없음)도 버려진다 — 이유가 남는다
        assertEquals("봉투 판 -1 ≠ 1", SnapshotEnvelope.read(CODEC, "{\"schema\":1,\"pluginVersion\":\"" + VERSION + "\"}", VERSION).rejected);
    }

    @Test
    public void 모르는_참조_종류는_그_한_건만_버린다() throws Exception {
        String json = SnapshotEnvelope.write(CODEC, sample(), null, VERSION)
                .replace("\"SCREEN\"", "\"FUTURE_TYPE\"");
        ScanResult after = SnapshotEnvelope.read(CODEC, json, VERSION).result;
        // 스냅샷 전체를 잃지 않는다. 필드는 그대로 있고 참조 하나만 빠진다.
        assertEquals(2, after.getFields().size());
        assertTrue(after.getField(10001L).getReferences(ReferenceType.SCREEN).isEmpty());
        assertEquals(2, after.getField(10001L).getTotalReferenceCount());
    }
}
