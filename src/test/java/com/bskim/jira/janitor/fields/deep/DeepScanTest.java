package com.bskim.jira.janitor.fields.deep;

import com.bskim.jira.janitor.fields.dao.DeepScanDao;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.scan.ScanFailure;
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
 * <p>SQL 문자열을 검사하는 테스트가 이상해 보일 수 있는데, <b>인용을 빠뜨리면
 * PostgreSQL 에서만 깨진다</b>(AO 테이블 이름은 대문자라 인용 없이 쓰면 소문자로
 * 접힌다). H2 로 개발하고 PostgreSQL 에 배포하는 이 프로젝트에서 그건 배포 후에야
 * 드러나는 결함이다. 그래서 문자열로 고정한다.
 */
public class DeepScanTest {

    private static DeepTable table() {
        return new DeepTable("AO_60DB71_ESTIMATESTATISTIC", "ID",
                Arrays.asList("FIELD_ID", "TYPE_ID"), 12L);
    }

    @Test
    public void 식별자를_인용한다() {
        String sql = DeepScanDao.selectSql(table(), "\"");
        assertTrue(sql, sql.contains("FROM \"AO_60DB71_ESTIMATESTATISTIC\""));
        assertTrue(sql, sql.contains("\"FIELD_ID\" LIKE"));
        assertTrue(sql, sql.startsWith("SELECT \"ID\", \"FIELD_ID\", \"TYPE_ID\" FROM"));
    }

    @Test
    public void 컬럼마다_OR_로_묶어_테이블당_쿼리_하나다() {
        String sql = DeepScanDao.selectSql(table(), "\"");
        assertEquals(1, sql.split("SELECT").length - 1);
        assertTrue(sql, sql.contains("\"FIELD_ID\" LIKE '%customfield!_%' ESCAPE '!' OR "
                + "\"TYPE_ID\" LIKE '%customfield!_%' ESCAPE '!'"));
    }

    @Test
    public void LIKE_의_밑줄을_이스케이프한다() {
        // customfield_ 의 _ 는 와일드카드가 아니라 리터럴이다(docs/00 21번과 같은 함정).
        assertTrue(DeepScanDao.selectSql(table(), "\"").contains("ESCAPE '!'"));
    }

    @Test
    public void 기본키가_없으면_컬럼만_고른다() {
        DeepTable noKey = new DeepTable("AO_ABC123_THING", null, Arrays.asList("BODY"), 3L);
        String sql = DeepScanDao.selectSql(noKey, "\"");
        assertTrue(sql, sql.startsWith("SELECT \"BODY\" FROM \"AO_ABC123_THING\""));
    }

    @Test
    public void 인용을_지원하지_않는_DB면_인용하지_않는다() {
        String sql = DeepScanDao.selectSql(table(), "");
        assertTrue(sql, sql.contains("FROM AO_60DB71_ESTIMATESTATISTIC"));
    }

    @Test
    public void 감사_로그_프리픽스는_건너뛴다() {
        // 거기 걸리는 것은 참조가 아니라 이력이다(docs/00 35번). 포함하면 한 번이라도
        // 설정된 모든 필드가 걸린다.
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
    public void 프리픽스만_낸다() {
        // AO 프리픽스로 앱 이름을 알 수 없다 — 설치된 플러그인 키 277개를 세 해시로
        // 대조했지만 어느 것과도 맞지 않았다(docs/00 35번).
        assertEquals("AO_60DB71", table().getPrefix());
    }

    @Test
    public void 결과_왕복() throws Exception {
        Map<Long, List<DeepHit>> hits = new LinkedHashMap<Long, List<DeepHit>>();
        hits.put(10001L, new ArrayList<DeepHit>(Arrays.asList(
                new DeepHit("AO_60DB71_ESTIMATESTATISTIC", "1"),
                new DeepHit("AO_60DB71_CARDLAYOUT", null))));
        DeepScanResult before = new DeepScanResult(new Date(1000L), new Date(2000L), 204,
                Arrays.asList("AO_C77861"), 12345L, hits,
                Arrays.asList(new ScanProblem("deep", "AO_X", "timeout")));

        DeepScanResult after = DeepSnapshotCodec.read(
                DeepSnapshotCodec.write(before, null, "1.3.1"), "1.3.1").result;

        assertEquals(204, after.getTablesScanned());
        assertEquals(12345L, after.getRowsCounted());
        assertEquals(Arrays.asList("AO_C77861"), after.getSkippedPrefixes());
        assertEquals(1, after.getProblems().size());
        assertEquals(2, after.getHits(10001L).size());
        assertEquals("AO_60DB71_ESTIMATESTATISTIC", after.getHits(10001L).get(0).getTable());
        assertNull(after.getHits(10001L).get(1).getRowId());
        assertTrue(after.getHits(99999L).isEmpty());
    }

    @Test
    public void 실패도_결과와_함께_저장된다() throws Exception {
        // 결과만 남기면 재기동 뒤 옛 요약이 흔적 없이 살아난다(docs/00 18·34번과 같은 모양).
        DeepScanResult result = new DeepScanResult(new Date(1000L), new Date(2000L), 10,
                Arrays.<String>asList(), 5L, new LinkedHashMap<Long, List<DeepHit>>(),
                Arrays.<ScanProblem>asList());
        ScanFailure failure = new ScanFailure(new Date(3000L), new Date(4000L), "SQLException: timeout");

        DeepSnapshotCodec.Snapshot back = DeepSnapshotCodec.read(
                DeepSnapshotCodec.write(result, failure, "1.3.1"), "1.3.1");

        assertEquals("SQLException: timeout", back.failure.getMessage());
        assertEquals(10, back.result.getTablesScanned());
        // 실패가 결과보다 나중이라는 사실이 남아야 "낡았다" 안내를 다시 그릴 수 있다.
        assertTrue(back.failure.getFinishedAt().after(back.result.getFinishedAt()));
    }

    @Test
    public void 판이_다르면_버린다() throws Exception {
        String json = DeepSnapshotCodec.write(new DeepScanResult(new Date(1L), new Date(2L), 1,
                Arrays.<String>asList(), 0L, new LinkedHashMap<Long, List<DeepHit>>(),
                Arrays.<ScanProblem>asList()), null, "1.2.1");
        assertNull(DeepSnapshotCodec.read(json, "1.3.1"));
        assertNull(DeepSnapshotCodec.read(null, "1.3.1"));
    }
}
