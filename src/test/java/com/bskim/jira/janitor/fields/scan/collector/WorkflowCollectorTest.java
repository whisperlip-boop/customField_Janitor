package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.DescriptorFactory;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 전이 → 화면 ID 해석 (실측 42번).
 *
 * <p>v1.0.0~v1.4.1 은 {@code Long.parseLong(action.getView())} 하나만 봤다. 뷰에는 화면
 * ID 가 아니라 뷰 이름이 들어 있어서 그 파싱은 <b>언제나</b> 실패했고, 실패가 조용히
 * 삼켜져 전이 화면 참조가 한 건도 안 나왔다. 그 회귀를 여기서 막는다.
 *
 * <p>기대값은 Jira 의 규칙({@code WorkflowActionsBean})이다 — 우리가 세는 것과 Jira 가
 * 전이 대화상자에 그리는 것이 같아야 한다.
 */
public class WorkflowCollectorTest {

    private static ActionDescriptor action(int id, String name, String view, String screenMeta) {
        ActionDescriptor action = DescriptorFactory.getFactory().createActionDescriptor();
        action.setId(id);
        action.setName(name);
        action.setView(view);
        Map<String, String> meta = new HashMap<String, String>();
        if (screenMeta != null) {
            meta.put(JiraWorkflow.ACTION_SCREEN_ATTRIBUTE, screenMeta);
        }
        action.setMetaAttributes(meta);
        return action;
    }

    /**
     * 옛 형식. 여기가 v1.4.1 까지의 결함 지점이다 — 뷰 이름을 숫자로 읽으려다 실패해
     * 0건이 됐다. docker 8.13 · atlas-run 8.17.1 의 기본 워크플로 전부가 이 형식이다.
     */
    @Test
    public void resolvesLegacyViewNames() {
        assertEquals(Long.valueOf(3L),
                WorkflowCollector.screenIdOf(action(2, "Close Issue", "resolveissue", null)));
        assertEquals(Long.valueOf(2L),
                WorkflowCollector.screenIdOf(action(3, "Reopen Issue", "commentassign", null)));
    }

    /** 요즘 형식. 워크플로 편집기는 {@code view="fieldscreen"} + 메타 속성으로 저장한다. */
    @Test
    public void readsScreenIdFromMetaAttribute() {
        assertEquals(Long.valueOf(10200L),
                WorkflowCollector.screenIdOf(action(11, "Fix", "fieldscreen", "10200")));
    }

    /**
     * 옛 이름이 메타보다 <b>먼저</b>다 — Jira 의 우선순위. 처음 구현은 메타를 먼저 봐서
     * 이런 전이에 Jira 가 그리지 않는 화면의 필드를 참조로 셌다(리뷰 지적).
     */
    @Test
    public void legacyViewNameWinsOverMeta() {
        assertEquals(Long.valueOf(3L),
                WorkflowCollector.screenIdOf(action(2, "Close Issue", "resolveissue", "10200")));
    }

    /** 뷰가 비어 있으면 화면 없음 — 남아 있는 메타는 무시한다(Jira 도 그린다). */
    @Test
    public void noViewMeansNoScreenEvenWithStaleMeta() {
        assertNull(WorkflowCollector.screenIdOf(action(1, "Create", null, null)));
        assertNull(WorkflowCollector.screenIdOf(action(1, "Create", "", "10200")));
        assertNull(WorkflowCollector.screenIdOf(null));
    }

    /**
     * 숫자 뷰는 Jira 가 받는 형식이 아니다 — 처음 구현은 "아주 오래된 인스턴스의 폴백"
     * 이라며 받아 줬는데 근거가 없었다. Jira 는 이 전이를 열 수 없으므로 예외가 맞고,
     * 호출부가 그 전이 한 건만 "확인 불가"로 남긴다.
     */
    @Test(expected = IllegalArgumentException.class)
    public void numericViewWithoutMetaIsAnError() {
        WorkflowCollector.screenIdOf(action(5, "Resolve", "3", null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownViewWithoutMetaIsAnError() {
        WorkflowCollector.screenIdOf(action(5, "Resolve", "fieldscreen", null));
    }

    @Test(expected = NumberFormatException.class)
    public void junkMetaIsAnError() {
        WorkflowCollector.screenIdOf(action(5, "Resolve", "fieldscreen", "not-a-number"));
    }

    /**
     * "어디서" 문자열에 전이 ID 가 들어간다. 한 워크플로 안에 같은 이름의 전이가 둘
     * 있기 때문이다 — {@code classic default workflow} 의 "Close Issue" 는 id 2·701 이고
     * 서로 다른 화면을 쓴다. 이름만 쓰면 {@code Reference} 의 중복 제거가 둘을 한 줄로
     * 합쳐, 실제로는 두 군데가 깨지는데 상세에는 한 군데만 보인다.
     */
    @Test
    public void whereStringSeparatesSameNamedTransitions() {
        String first = WorkflowCollector.whereOf(action(2, "Close Issue", "resolveissue", null));
        String second = WorkflowCollector.whereOf(action(701, "Close Issue", "commentassign", null));

        assertEquals("transition: Close Issue (#2)", first);
        assertEquals("transition: Close Issue (#701)", second);
    }
}
