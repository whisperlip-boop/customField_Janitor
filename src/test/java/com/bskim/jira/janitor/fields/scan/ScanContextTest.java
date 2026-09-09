package com.bskim.jira.janitor.fields.scan;

import com.bskim.jira.janitor.fields.model.FieldUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 문자열에서 필드 참조를 찾아내는 로직. {@code FieldUsage} 컬렉션만 받으므로
 * Jira 없이 돌아간다.
 *
 * <p>이게 오탐 방지의 핵심이다(docs/00-환경실측.md 4번). 워크플로 arg 값에는
 * 해결책·상태·화면·스텝 ID가 섞여 있고 전부 커스텀 필드와 같은 10000번대를 쓴다.
 * 숫자 단독 매칭을 무분별하게 하면 [위험] 라벨이 무의미해진다 — 그 라벨은 삭제를
 * 막는 가장 강한 신호라 오탐 비용이 크다.
 */
public class ScanContextTest {

    private static ScanContext context(long... ids) {
        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        for (long id : ids) {
            fields.add(new FieldUsage(id, "Field " + id, "type", "Type"));
        }
        return new ScanContext(fields);
    }

    private static List<Long> ids(Set<FieldUsage> hits) {
        List<Long> out = new ArrayList<Long>();
        for (FieldUsage f : hits) {
            out.add(f.getNumericId());
        }
        return out;
    }

    @Test
    public void customfield_표기를_찾는다() {
        ScanContext c = context(10001L);
        assertEquals(Arrays.asList(10001L),
                ids(c.findReferencedFields("<arg name=\"field.id\">customfield_10001</arg>")));
    }

    @Test
    public void 우리_목록에_없는_ID는_무시한다() {
        ScanContext c = context(10001L);
        assertTrue(c.findReferencedFields("customfield_99999").isEmpty());
    }

    @Test
    public void 확정_탐색은_숫자_단독을_세지_않는다() {
        // 기본(확정) 경로는 customfield_ 표기만 본다. 이게 [위험] 판정의 근거다.
        ScanContext c = context(10001L);
        assertTrue(c.findReferencedFields("10001").isEmpty());
    }

    /**
     * {@code customfield_10001} 안의 "10001"을 숫자 단독으로 <b>또</b> 세지 않는다.
     * 세면 같은 참조가 확정과 추정 양쪽에 잡혀 수가 두 배가 된다.
     */
    @Test
    public void customfield_표기_안의_숫자를_중복으로_세지_않는다() {
        ScanContext c = context(10001L);
        Set<FieldUsage> hits = c.findReferencedFieldsIncludingBareIds("customfield_10001");
        assertEquals(1, hits.size());
        assertEquals(Arrays.asList(10001L), ids(hits));
    }

    /**
     * docs/00 4번의 실제 오탐 사례. {@code <arg name="field.value">10000</arg>} 의
     * 10000은 해결책 ID였는데 커스텀 필드 10000과 겹쳐 Development 필드가 [위험]으로
     * 찍혔다. 추정 경로에서는 여전히 잡히는 것이 정상이고(그래서 추정이다),
     * 확정 경로에서는 잡히지 않아야 한다.
     */
    @Test
    public void 해결책_ID_오탐은_추정_경로에만_잡힌다() {
        ScanContext c = context(10000L);
        String arg = "<arg name=\"field.value\">10000</arg>";
        assertTrue("확정 경로에는 안 잡혀야 한다", c.findReferencedFields(arg).isEmpty());
        assertEquals("추정 경로에는 잡힌다", 1, c.findReferencedFieldsIncludingBareIds(arg).size());
    }

    /** 여러 개가 섞인 문자열에서 consumed 위치 계산이 맞는지. */
    @Test
    public void 확정과_추정이_섞여도_위치_계산이_맞다() {
        ScanContext c = context(10001L, 10002L, 10003L);
        String haystack = "customfield_10001 and 10002 and customfield_10003";
        assertEquals(Arrays.asList(10001L, 10003L),
                ids(c.findReferencedFields(haystack)));
        Set<FieldUsage> all = c.findReferencedFieldsIncludingBareIds(haystack);
        assertEquals(3, all.size());
    }

    @Test
    public void 두_자리_숫자는_추정에서도_무시한다() {
        // \d{3,} 이므로 짧은 숫자는 안 본다. 필드 ID가 세 자리 미만인 인스턴스는 없다.
        ScanContext c = context(99L);
        assertTrue(c.findReferencedFieldsIncludingBareIds("99").isEmpty());
    }

    @Test
    public void null_과_빈_문자열은_빈_결과다() {
        ScanContext c = context(10001L);
        assertTrue(c.findReferencedFields(null).isEmpty());
        assertTrue(c.findReferencedFields("").isEmpty());
        assertTrue(c.findReferencedFieldsIncludingBareIds(null).isEmpty());
    }

    @Test
    public void 확인_불가는_조용히_사라지지_않는다() {
        ScanContext c = context(10001L);
        c.addProblem("filter", "깨진 필터 (1)", "JQL 파싱 실패");
        c.addProblem("scheme", "스킴 (2)", new IllegalStateException("boom"));
        assertEquals(2, c.getProblems().size());
        assertTrue(c.getProblems().get(1).getMessage().contains("IllegalStateException"));
    }
}
