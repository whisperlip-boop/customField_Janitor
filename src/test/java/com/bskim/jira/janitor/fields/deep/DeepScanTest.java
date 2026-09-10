package com.bskim.jira.janitor.fields.deep;

import com.bskim.jira.janitor.fields.dao.DeepScanDao;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.scan.ScanFailure;
import com.bskim.jira.janitor.fields.store.SnapshotEnvelope;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import org.junit.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 심층 스캔에서 DB 없이 고정할 수 있는 것들.
 *
 * <p>SQL 문자열을 검사하는 테스트가 이상해 보일 수 있는데, <b>인용·스키마를 빠뜨리면
 * PostgreSQL 에서만 깨진다</b>(AO 테이블 이름은 대문자, schema-name 은 search_path 에
 * 없을 수 있다). H2 로 개발하고 PostgreSQL 에 배포하는 이 프로젝트에서 그건 배포 후에야
 * 드러나는 결함이다. 그래서 문자열로 고정한다.
 */
public class DeepScanTest {

    private static DeepTable table() {
        return new DeepTable("AO_60DB71_ESTIMATESTATISTIC", "ID",
                Arrays.asList("FIELD_ID", "TYPE_ID"));
    }

    @Test
    public void 식별자를_인용한다() {
        String sql = DeepScanDao.selectSql(table(), "\"", null);
        assertTrue(sql, sql.contains("FROM \"AO_60DB71_ESTIMATESTATISTIC\""));
        assertTrue(sql, sql.contains("\"FIELD_ID\" LIKE"));
        assertTrue(sql, sql.startsWith("SELECT \"ID\", \"FIELD_ID\", \"TYPE_ID\" FROM"));
    }

    @Test
    public void 스키마가_있으면_붙인다() {
        // schema-name 이 search_path 에 없는 PostgreSQL 에서 안 붙이면 모든 테이블이
        // "relation does not exist" 로 죽고, 결과는 "200개를 훑었는데 0건"으로 완전해 보인다.
        String sql = DeepScanDao.selectSql(table(), "\"", "jiraschema");
        assertTrue(sql, sql.contains("FROM jiraschema.\"AO_60DB71_ESTIMATESTATISTIC\""));
    }

    @Test
    public void 컬럼마다_OR_로_묶어_테이블당_쿼리_하나다() {
        String sql = DeepScanDao.selectSql(table(), "\"", null);
        assertEquals(1, sql.split("SELECT").length - 1);
        assertTrue(sql, sql.contains("\"FIELD_ID\" LIKE '%customfield!_%' ESCAPE '!' OR "
                + "\"TYPE_ID\" LIKE '%customfield!_%' ESCAPE '!'"));
    }

    @Test
    public void 기본키가_있으면_그_순서로_정렬한다() {
        // 상한이 있는 조회에 순서가 없으면 DB 가 임의의 500행을 주고 두 번 돌리면 결과가 다르다.
        assertTrue(DeepScanDao.selectSql(table(), "\"", null).endsWith(" ORDER BY \"ID\""));
    }

    @Test
    public void 기본키가_없으면_컬럼만_고르고_정렬도_없다() {
        DeepTable noKey = new DeepTable("AO_ABC123_THING", null, Arrays.asList("BODY"));
        String sql = DeepScanDao.selectSql(noKey, "\"", null);
        assertTrue(sql, sql.startsWith("SELECT \"BODY\" FROM \"AO_ABC123_THING\""));
        assertFalse(sql, sql.contains("ORDER BY"));
    }

    @Test
    public void 인용을_지원하지_않는_DB면_인용하지_않는다() {
        assertTrue(DeepScanDao.selectSql(table(), "", null).contains("FROM AO_60DB71_ESTIMATESTATISTIC"));
    }

    @Test
    public void 메타데이터_패턴의_이스케이프는_드라이버_것을_쓴다() {
        // Oracle 은 '/' 다. '\' 를 박아 두면 그 DB 에서 테이블 0개가 나오고 스캔은 "성공"한다.
        assertEquals("AO\\_%", DeepScanDao.tablePattern("\\"));
        assertEquals("AO/_%", DeepScanDao.tablePattern("/"));
        assertEquals("AO\\_%", DeepScanDao.tablePattern(null));
    }

    @Test
    public void 감사_로그_프리픽스는_건너뛴다() {
        assertTrue(DeepScanDao.isSkipped("AO_C77861_AUDIT_ENTITY"));
        assertTrue(DeepScanDao.isSkipped("AO_C77861_AUDIT_CHANGED_VALUE"));
        assertFalse(DeepScanDao.isSkipped("AO_60DB71_ESTIMATESTATISTIC"));
    }

    @Test
    public void 문자열_컬럼만_본다() {
        assertTrue(DeepScanDao.isText(Types.VARCHAR));
        assertTrue(DeepScanDao.isText(Types.CLOB));
        assertTrue(DeepScanDao.isText(Types.LONGNVARCHAR));
        assertFalse(DeepScanDao.isText(Types.BLOB));
        assertFalse(DeepScanDao.isText(Types.BIGINT));
        assertFalse(DeepScanDao.isText(Types.VARBINARY));
    }

    @Test
    public void 테이블_일치는_표본을_넘어도_건수를_잃지_않는다() {
        // 전에는 20건에서 잘라 버리고 제목이 "(20)"을 총 건수인 척했다(리뷰 지적).
        DeepTableMatch match = new DeepTableMatch("AO_X_Y");
        for (int i = 0; i < 300; i++) {
            match.add(String.valueOf(i));
        }
        assertEquals(300, match.getMatchCount());
        assertEquals(DeepScanPolicy.SAMPLE_ROWS, match.getSampleRowIds().size());
        assertEquals(280, match.getOverflow());
    }

    @Test
    public void 기본키가_없는_행도_건수는_센다() {
        DeepTableMatch match = new DeepTableMatch("AO_X_Y");
        match.add(null);
        match.add(null);
        assertEquals(2, match.getMatchCount());
        assertTrue(match.getSampleRowIds().isEmpty());
    }

    private static DeepScanResult sample() {
        Map<Long, List<DeepTableMatch>> matches = new LinkedHashMap<Long, List<DeepTableMatch>>();
        DeepTableMatch a = new DeepTableMatch("AO_60DB71_ESTIMATESTATISTIC");
        a.add("1");
        DeepTableMatch b = new DeepTableMatch("AO_60DB71_CARDLAYOUT");
        for (int i = 0; i < 25; i++) {
            b.add(null);
        }
        matches.put(10001L, new ArrayList<DeepTableMatch>(Arrays.asList(a, b)));
        return new DeepScanResult(new Date(1000L), new Date(2000L), 204, 12345L, matches,
                Arrays.asList(new ScanProblem("deep", "AO_X", "timeout")));
    }

    @Test
    public void 결과_왕복() throws Exception {
        DeepScanResult after = SnapshotEnvelope.read(DeepResultCodec.INSTANCE, 
                SnapshotEnvelope.write(DeepResultCodec.INSTANCE, sample(), null, "1.3.2"), "1.3.2").result;

        assertEquals(204, after.getTablesScanned());
        assertEquals(12345L, after.getRowsCounted());
        assertEquals(1, after.getProblems().size());
        assertEquals(2, after.getMatches(10001L).size());
        assertEquals("AO_60DB71_ESTIMATESTATISTIC", after.getMatches(10001L).get(0).getTable());
        assertEquals(Arrays.asList("1"), after.getMatches(10001L).get(0).getSampleRowIds());
        // 표본 없는 25건도 건수는 살아 있다.
        assertEquals(25, after.getMatches(10001L).get(1).getMatchCount());
        assertEquals(26, after.getMatchCount(10001L));
        assertTrue(after.getMatches(99999L).isEmpty());
    }

    @Test
    public void 실패도_결과와_함께_저장된다() throws Exception {
        ScanFailure failure = new ScanFailure(new Date(3000L), new Date(4000L), "SQLException: timeout");
        SnapshotEnvelope.Snapshot<DeepScanResult> back = SnapshotEnvelope.read(DeepResultCodec.INSTANCE, 
                SnapshotEnvelope.write(DeepResultCodec.INSTANCE, sample(), failure, "1.3.2"), "1.3.2");
        assertEquals("SQLException: timeout", back.failure.getMessage());
        assertEquals(204, back.result.getTablesScanned());
        assertTrue(back.failure.getFinishedAt().after(back.result.getFinishedAt()));
    }

    @Test
    public void 판이_다르면_버린다() throws Exception {
        String json = SnapshotEnvelope.write(DeepResultCodec.INSTANCE, sample(), null, "1.3.1");
        assertTrue(SnapshotEnvelope.read(DeepResultCodec.INSTANCE, json, "1.3.2").isRejected());
        assertNull(SnapshotEnvelope.read(DeepResultCodec.INSTANCE, null, "1.3.2"));
        // 옛 스키마(1: 행 단위 hits) 는 판이 달라 버려진다.
        assertEquals("결과 판 1 ≠ 2", SnapshotEnvelope.read(DeepResultCodec.INSTANCE, "{\"envelope\":1,\"schema\":1,\"pluginVersion\":\"1.3.2\",\"result\":null}", "1.3.2").rejected);
    }

    @Test
    public void 두_컬럼에_같은_필드가_있어도_행마다_한_번만_센다() {
        ScanContext context = new ScanContext(Arrays.asList(
                new FieldUsage(10302L, "A", "t", "t"), new FieldUsage(10303L, "B", "t", "t")));
        Map<Long, List<DeepTableMatch>> matches = new LinkedHashMap<Long, List<DeepTableMatch>>();
        DeepScanService.record(matches, context, "AO_X", "1",
                Arrays.asList("customfield_10302 여기", "그리고 customfield_10302 또", "customfield_10303"));
        DeepScanService.record(matches, context, "AO_X", "2", Arrays.asList("customfield_10302"));
        DeepScanService.record(matches, context, "AO_X", "3", Arrays.asList("아무것도 없음"));
        assertEquals(2, matches.get(10302L).get(0).getMatchCount());
        assertEquals(Arrays.asList("1", "2"), matches.get(10302L).get(0).getSampleRowIds());
        assertEquals(1, matches.get(10303L).get(0).getMatchCount());
        assertEquals(1, matches.get(10302L).size());
    }
}
