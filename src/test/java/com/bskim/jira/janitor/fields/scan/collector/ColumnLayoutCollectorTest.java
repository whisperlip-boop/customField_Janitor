package com.bskim.jira.janitor.fields.scan.collector;

import com.bskim.jira.janitor.fields.dao.ColumnLayoutRow;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 컬럼 설정 세 종류의 분류와 개인 설정 합치기.
 *
 * <p>{@code collect(context, rows)} 로 DAO를 건너뛴다. 사용자 이름 조회는
 * Jira 없이 돌면 {@code ComponentAccessor} 가 예외를 내는데, 수집기가 그것을
 * 잡고 키를 그대로 쓰므로 이 테스트는 Jira 없이 돈다 — <b>그 폴백이 살아 있는지도
 * 함께 고정한다.</b> 폴백이 사라지면 여기서 먼저 깨진다.
 */
public class ColumnLayoutCollectorTest {

    private static final String CF = "customfield_10001";

    private static ScanContext context() {
        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        fields.add(new FieldUsage(10001L, "Story Points", "type", "Number"));
        return new ScanContext(fields);
    }

    private static List<Reference> refs(ScanContext context) {
        return context.getFields().iterator().next().getReferences(ReferenceType.COLUMN_LAYOUT);
    }

    private static void collect(ScanContext context, ColumnLayoutRow... rows) {
        new ColumnLayoutCollector().collect(context, Arrays.asList(rows));
    }

    @Test
    public void 시스템_기본_컬럼은_이름_대신_i18n_키를_남긴다() {
        ScanContext c = context();
        collect(c, new ColumnLayoutRow(10000L, null, null, null, CF));

        assertEquals(1, refs(c).size());
        Reference ref = refs(c).get(0);
        // 대상 이름이 데이터에 없다. 화면이 키로 그리도록 비워 둔다(detail.vm 규칙).
        assertNull(ref.getTargetName());
        assertEquals("janitor.fields.ref.columnLayout.system", ref.getDetailI18nKey());
        // 없는 관리 화면으로 링크를 보내지 않는다(실측: 8.13 에서 404).
        assertNull(ref.getAdminUrl());
    }

    @Test
    public void 필터_컬럼은_필터_이름과_링크를_준다() {
        ScanContext c = context();
        collect(c, new ColumnLayoutRow(10100L, null, 10500L, "열린 이슈", CF));

        Reference ref = refs(c).get(0);
        assertEquals("열린 이슈", ref.getTargetName());
        assertEquals("janitor.fields.ref.columnLayout.filter", ref.getDetailI18nKey());
        assertNotNull(ref.getAdminUrl());
        assertTrue(ref.getAdminUrl().contains("10500"));
    }

    @Test
    public void 필터가_사라진_컬럼_설정도_버리지_않는다() {
        ScanContext c = context();
        collect(c, new ColumnLayoutRow(10100L, null, 10500L, null, CF));

        assertEquals(1, refs(c).size());
        assertEquals("(filter 10500)", refs(c).get(0).getTargetName());
    }

    @Test
    public void 필터_컬럼_둘은_합쳐지지_않는다() {
        // v1.0.1 회귀와 같은 종류. 동일성 키에 대상 구분이 없으면 조용히 1건이 된다.
        ScanContext c = context();
        collect(c,
                new ColumnLayoutRow(10100L, null, 10500L, "필터 하나", CF),
                new ColumnLayoutRow(10101L, null, 10501L, "필터 둘", CF));

        assertEquals(2, refs(c).size());
    }

    @Test
    public void 개인_설정은_필드마다_한_건으로_합친다() {
        ScanContext c = context();
        collect(c,
                new ColumnLayoutRow(10200L, "JIRAUSER10000", null, null, CF),
                new ColumnLayoutRow(10201L, "JIRAUSER10100", null, null, CF),
                new ColumnLayoutRow(10202L, "JIRAUSER10200", null, null, CF));

        assertEquals(1, refs(c).size());
        Reference ref = refs(c).get(0);
        assertEquals("janitor.fields.ref.columnLayout.personal", ref.getDetailI18nKey());
        // 몇 명인지가 앞에 온다. 이름은 조회가 안 되면 키 그대로다.
        assertTrue(ref.getDetail(), ref.getDetail().startsWith("3 · "));
        assertTrue(ref.getDetail(), ref.getDetail().contains("JIRAUSER10100"));
    }

    @Test
    public void 개인_설정이_많으면_이름을_줄인다() {
        ScanContext c = context();
        List<ColumnLayoutRow> rows = new ArrayList<ColumnLayoutRow>();
        for (int i = 0; i < 8; i++) {
            rows.add(new ColumnLayoutRow(10200L + i, "JIRAUSER1010" + i, null, null, CF));
        }
        new ColumnLayoutCollector().collect(c, rows);

        String detail = refs(c).get(0).getDetail();
        assertTrue(detail, detail.startsWith("8 · "));
        assertTrue(detail, detail.endsWith(", +3"));
    }

    @Test
    public void 같은_사용자가_여러_행이어도_한_명이다() {
        ScanContext c = context();
        collect(c,
                new ColumnLayoutRow(10200L, "JIRAUSER10000", null, null, CF),
                new ColumnLayoutRow(10200L, "JIRAUSER10000", null, null, CF));

        assertTrue(refs(c).get(0).getDetail().startsWith("1 · "));
    }

    @Test
    public void 지워진_필드의_컬럼_설정은_무시한다() {
        ScanContext c = context();
        collect(c, new ColumnLayoutRow(10000L, null, null, null, "customfield_99999"));

        assertTrue(refs(c).isEmpty());
    }

    @Test
    public void 컬럼_참조는_사용_증거이고_위험은_아니다() {
        assertTrue(ReferenceType.COLUMN_LAYOUT.isEvidence());
        assertFalse(ReferenceType.COLUMN_LAYOUT.isRisky());
    }
}
