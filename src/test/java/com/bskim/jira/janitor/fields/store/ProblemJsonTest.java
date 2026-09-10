package com.bskim.jira.janitor.fields.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import com.atlassian.jira.util.json.JSONArray;
import com.bskim.jira.janitor.fields.model.ScanProblem;

import org.junit.Test;

/**
 * "확인 불가" 항목의 저장 규칙. 사람이 읽는 문장은 <b>키와 인자</b>로 저장되어야 한다 —
 * 완성된 문장을 저장하면 스캔한 사람의 언어가 모든 화면에 박힌다(docs/00 41번).
 */
public class ProblemJsonTest {

    @Test
    public void 키와_인자가_왕복한다() throws Exception {
        List<ScanProblem> back = ProblemJson.read(ProblemJson.write(Arrays.asList(
                ScanProblem.keyed("filter", "깨진 필터 (10102)",
                        "janitor.fields.problem.filterJql", "JqlParseException"))));
        assertEquals(1, back.size());
        assertEquals("filter", back.get(0).getArea());
        assertEquals("janitor.fields.problem.filterJql", back.get(0).getMessageKey());
        assertEquals(Arrays.asList("JqlParseException"), back.get(0).getMessageArgs());
    }

    @Test
    public void 인자가_없는_문구도_왕복한다() throws Exception {
        List<ScanProblem> back = ProblemJson.read(ProblemJson.write(Arrays.asList(
                ScanProblem.keyed("permissionScheme", "-", "janitor.fields.problem.schemeManager"))));
        assertEquals("janitor.fields.problem.schemeManager", back.get(0).getMessageKey());
        assertTrue(back.get(0).getMessageArgs().isEmpty());
    }

    @Test
    public void 예외_텍스트는_번역하지_않고_그대로_간다() throws Exception {
        List<ScanProblem> back = ProblemJson.read(ProblemJson.write(Arrays.asList(
                ScanProblem.raw("workflow", "Janitor Fixture Workflow", "IllegalStateException: boom"))));
        assertNull(back.get(0).getMessageKey());
        assertEquals("IllegalStateException: boom", back.get(0).getMessage());
        // i18n 을 못 얻어도(테스트 JVM) 화면에 낼 것이 있다.
        assertEquals("IllegalStateException: boom", back.get(0).resolve(null));
    }

    @Test
    public void 키가_없는_옛_스냅샷은_문장을_그대로_읽는다() throws Exception {
        JSONArray old = new JSONArray("[{\"area\":\"filter\",\"target\":\"x\",\"message\":\"JQL 파싱 실패\"}]");
        List<ScanProblem> back = ProblemJson.read(old);
        assertNull(back.get(0).getMessageKey());
        assertEquals("JQL 파싱 실패", back.get(0).resolve(null));
    }

    @Test
    public void i18n_을_못_얻으면_키를_낸다() {
        // 빈 화면보다 낫다 — LocaleText 와 같은 규칙.
        assertEquals("janitor.fields.problem.fieldGone",
                ScanProblem.keyed("context", "x", "janitor.fields.problem.fieldGone").resolve(null));
    }
}
