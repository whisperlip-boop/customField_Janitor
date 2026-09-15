package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowActionsBean;
import com.atlassian.jira.workflow.WorkflowManager;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ConditionDescriptor;
import com.opensymphony.workflow.loader.ConditionsDescriptor;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import com.opensymphony.workflow.loader.ResultDescriptor;
import com.opensymphony.workflow.loader.RestrictionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.ValidatorDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 워크플로 참조 (기획서 5.3(5)). [위험] 라벨의 근거를 만드는 수집기다.
 *
 * <p>필드 ID는 조건/검증기/함수의 <b>임의의 arg 키</b>에 들어간다. JSU / JMWE /
 * ScriptRunner 같은 서드파티는 키 이름을 각자 정하므로 키로 찾을 수 없다.
 * 그래서 arg 맵의 <b>모든 값</b>에 대해 문자열 스캔을 한다(기획서 결정 5).
 *
 * <p>순회해야 하는 곳이 기획서에 적힌 것보다 많다. OSWorkflow에서
 * {@code ActionDescriptor.getPostFunctions()}는 사실상 pre-function 자리이고,
 * Jira UI가 "후처리 함수"라고 부르는 것은 <b>결과(ResultDescriptor)</b>에 달려 있다.
 * 여기를 빼면 가장 흔한 참조인 "필드 값 업데이트" 후처리 함수를 통째로 놓친다.
 * 그래서 아래를 모두 훑는다:
 *
 * <pre>
 *   전이: validators, preFunctions, postFunctions
 *         unconditionalResult / conditionalResults[*] 의 validators·pre·postFunctions
 *         restriction의 conditions (중첩 ConditionsDescriptor 재귀)
 *   스텝: preFunctions, postFunctions
 *   전이 화면: Jira 의 해석기(WorkflowActionsBean)로 전이 → 화면 ID → 그 화면의 필드
 * </pre>
 *
 * <p>전이 화면의 화면 ID 는 {@code action.getView()} 가 아니다 — {@link #screenIdOf} 참고
 * (실측 42번).
 *
 * <p>전이 화면에서 온 참조는 위험으로 세지 않는다. 근거는 {@link Reference} 주석 참고.
 */
public class WorkflowCollector implements ReferenceCollector {

    private static final String KEY_TRANSITION_SCREEN = "janitor.fields.wf.transitionScreen";
    private static final String KEY_CONDITION = "janitor.fields.wf.condition";
    private static final String KEY_VALIDATOR = "janitor.fields.wf.validator";
    private static final String KEY_PRE_FUNCTION = "janitor.fields.wf.preFunction";
    private static final String KEY_POST_FUNCTION = "janitor.fields.wf.postFunction";
    private static final String KEY_STEP_FUNCTION = "janitor.fields.wf.stepFunction";
    private static final String KEY_GUESSED = "janitor.fields.wf.numericGuess";

    /** Jira가 기본 제공하는 함수/검증기/조건의 구현 클래스 접두사. */
    private static final String JIRA_CORE_PREFIX = "com.atlassian.jira.";

    private Map<Long, List<String>> fieldIdsByScreenId;

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.WORKFLOWS;
    }

    @Override
    public void collect(ScanContext context) {
        fieldIdsByScreenId = indexScreenFields();
        Map<String, Set<String>> projectsByWorkflow = buildProjectMap(context);

        WorkflowManager workflowManager = ComponentAccessor.getWorkflowManager();
        for (JiraWorkflow workflow : workflowManager.getWorkflows()) {
            List<String> projects = new ArrayList<String>();
            Set<String> mapped = projectsByWorkflow.get(workflow.getName());
            if (mapped != null) {
                projects.addAll(mapped);
            }
            try {
                collectWorkflow(context, workflow, projects);
            } catch (Throwable e) {
                // RuntimeException 만 잡으면 8.13 컴파일 → 8.17.1 실행의 어긋남이 내는
                // LinkageError 가 빠져나가 뒤에 남은 워크플로를 전부 건너뛴다(CLAUDE.md 함정).
                context.addProblem("workflow", workflow.getName(), e);
            }
        }
    }

    private void collectWorkflow(ScanContext context, JiraWorkflow workflow, List<String> projects) {
        for (ActionDescriptor action : workflow.getAllActions()) {
            String where = whereOf(action);

            // 전이 화면에 놓인 필드. 해석 실패는 이 전이 한 건만 "확인 불가"로 남기고
            // 조건/검증기/함수 스캔은 그대로 진행한다(보조 재료, 규칙 7).
            Long screenId;
            try {
                screenId = screenIdOf(action);
            } catch (RuntimeException e) {
                context.addProblem("workflowScreen", workflow.getName() + " / " + where, e);
                screenId = null;
            }
            addScreenFields(context, workflow, screenId, where, projects);

            scanValidators(context, workflow, action.getValidators(), where, projects);
            scanFunctions(context, workflow, action.getPreFunctions(), where, KEY_PRE_FUNCTION, projects);
            scanFunctions(context, workflow, action.getPostFunctions(), where, KEY_POST_FUNCTION, projects);

            scanResult(context, workflow, action.getUnconditionalResult(), where, projects);
            if (action.getConditionalResults() != null) {
                for (Object raw : action.getConditionalResults()) {
                    if (raw instanceof ResultDescriptor) {
                        scanResult(context, workflow, (ResultDescriptor) raw, where, projects);
                    }
                }
            }

            RestrictionDescriptor restriction = action.getRestriction();
            if (restriction != null) {
                scanConditions(context, workflow, restriction.getConditionsDescriptor(), where, projects, 0);
            }
        }

        // 스텝 단위 함수. getAllActions()로는 안 잡힌다.
        if (workflow.getDescriptor() != null && workflow.getDescriptor().getSteps() != null) {
            for (Object raw : workflow.getDescriptor().getSteps()) {
                if (!(raw instanceof StepDescriptor)) {
                    continue;
                }
                StepDescriptor step = (StepDescriptor) raw;
                String where = "step: " + step.getName();
                scanFunctions(context, workflow, step.getPreFunctions(), where, KEY_STEP_FUNCTION, projects);
                scanFunctions(context, workflow, step.getPostFunctions(), where, KEY_STEP_FUNCTION, projects);
            }
        }
    }

    private void addScreenFields(ScanContext context, JiraWorkflow workflow, Long screenId,
                                 String where, List<String> projects) {
        if (screenId == null) {
            return;
        }
        List<String> fieldIds = fieldIdsByScreenId.get(screenId);
        if (fieldIds == null) {
            return;
        }
        for (String fieldId : fieldIds) {
            add(context, context.byFieldId(fieldId), workflow, where, KEY_TRANSITION_SCREEN, projects, false);
        }
    }

    /**
     * 참조의 "어디서" 문자열. <b>전이 ID를 함께 넣는다</b> — 한 워크플로 안에 같은 이름의
     * 전이가 둘 있는 것이 흔하기 때문이다(실측: {@code classic default workflow} 의
     * "Close Issue" 는 id 2·701 둘이고 각각 다른 화면을 쓴다). 이름만 쓰면 두 전이의
     * 참조가 {@link Reference} 의 중복 제거로 한 줄에 합쳐져, 실제로는 두 군데가 깨지는데
     * 상세 화면에는 한 군데만 보인다. "UI에는 항상 ID를 함께 노출한다"(CLAUDE.md 규칙 2)와도
     * 같은 방향이다.
     */
    static String whereOf(ActionDescriptor action) {
        return "transition: " + action.getName() + " (#" + action.getId() + ")";
    }

    private void scanResult(ScanContext context, JiraWorkflow workflow, ResultDescriptor result,
                            String where, List<String> projects) {
        if (result == null) {
            return;
        }
        scanValidators(context, workflow, result.getValidators(), where, projects);
        scanFunctions(context, workflow, result.getPreFunctions(), where, KEY_PRE_FUNCTION, projects);
        // Jira UI의 "후처리 함수(post function)"가 바로 이 자리다.
        scanFunctions(context, workflow, result.getPostFunctions(), where, KEY_POST_FUNCTION, projects);
    }

    private void scanFunctions(ScanContext context, JiraWorkflow workflow, Collection<?> functions,
                               String where, String positionKey, List<String> projects) {
        if (functions == null) {
            return;
        }
        for (Object raw : functions) {
            if (!(raw instanceof FunctionDescriptor)) {
                continue;
            }
            FunctionDescriptor function = (FunctionDescriptor) raw;
            addAll(context, workflow, function.getArgs(), where, positionKey, projects, true);
        }
    }

    private void scanValidators(ScanContext context, JiraWorkflow workflow, Collection<?> validators,
                                String where, List<String> projects) {
        if (validators == null) {
            return;
        }
        for (Object raw : validators) {
            if (!(raw instanceof ValidatorDescriptor)) {
                continue;
            }
            ValidatorDescriptor validator = (ValidatorDescriptor) raw;
            addAll(context, workflow, validator.getArgs(), where, KEY_VALIDATOR, projects, true);
        }
    }

    /**
     * 조건은 AND/OR로 중첩된다(함정 3). 재귀로 끝까지 내려간다.
     * 깊이 제한은 순환 참조가 있을 수 없는 구조라 방어용일 뿐이다.
     */
    private void scanConditions(ScanContext context, JiraWorkflow workflow, ConditionsDescriptor conditions,
                                String where, List<String> projects, int depth) {
        if (conditions == null || depth > 20) {
            return;
        }
        List<?> children = conditions.getConditions();
        if (children == null) {
            return;
        }
        for (Object raw : children) {
            if (raw instanceof ConditionsDescriptor) {
                scanConditions(context, workflow, (ConditionsDescriptor) raw, where, projects, depth + 1);
            } else if (raw instanceof ConditionDescriptor) {
                ConditionDescriptor condition = (ConditionDescriptor) raw;
                addAll(context, workflow, condition.getArgs(), where, KEY_CONDITION, projects, true);
            }
        }
    }

    /**
     * arg 맵의 모든 값을 문자열로 훑는다. 키 이름은 신뢰할 수 없다(결정 5).
     *
     * <p>확정 참조({@code customfield_<id>})와 추정 참조(숫자 ID 단독)를 구분한다.
     * 근거: Jira 기본 워크플로의 {@code <arg name="field.value">10000</arg>}(해결책 ID)가
     * 커스텀 필드 10000과 숫자로 충돌한다. 구분하지 않으면 기본 필드가 전부
     * [위험]으로 찍혀 라벨이 의미를 잃는다(실측).
     *
     * <p>Jira 기본 제공 구현({@code com.atlassian.jira.*})은 커스텀 필드를 항상
     * {@code customfield_<id>} 형태로 넣기 때문에 숫자 단독 탐색을 아예 끈다.
     * 서드파티 구현에서만 숫자 단독을 보고, 그 결과는 추정으로 표시하며
     * 위험으로 세지 않는다.
     */
    private void addAll(ScanContext context, JiraWorkflow workflow, Map<?, ?> args,
                        String where, String positionKey, List<String> projects, boolean risky) {
        if (args == null) {
            return;
        }
        boolean jiraCore = isJiraCoreImplementation(args);

        for (Object value : args.values()) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            Set<FieldUsage> certain = context.findReferencedFields(text);
            for (FieldUsage field : certain) {
                add(context, field, workflow, where, positionKey, projects, risky);
            }
            if (jiraCore) {
                continue;
            }
            for (FieldUsage field : context.findReferencedFieldsIncludingBareIds(text)) {
                if (certain.contains(field)) {
                    continue;
                }
                add(context, field, workflow, where + " (" + shortPosition(positionKey) + ")",
                        KEY_GUESSED, projects, false);
            }
        }
    }

    /** i18n 키에서 위치 이름만 뽑는다. 추정 참조의 상세에 "어디서 나왔는지"를 남기려는 것. */
    private static String shortPosition(String positionKey) {
        int dot = positionKey.lastIndexOf('.');
        return dot < 0 ? positionKey : positionKey.substring(dot + 1);
    }

    /**
     * 이 디스크립터가 Jira 기본 제공 구현인지. OSWorkflow는 구현 클래스를
     * {@code class.name} arg에 담는다.
     */
    private boolean isJiraCoreImplementation(Map<?, ?> args) {
        Object className = args.get("class.name");
        return className != null && String.valueOf(className).startsWith(JIRA_CORE_PREFIX);
    }

    private void add(ScanContext context, FieldUsage field, JiraWorkflow workflow,
                     String where, String positionKey, List<String> projects, boolean risky) {
        if (field == null) {
            return;
        }
        context.addReference(field, new Reference(
                ReferenceType.WORKFLOW,
                workflow.getName(),
                workflow.getName(),
                where,
                positionKey,
                AdminUrls.workflow(workflow.getName()),
                projects,
                risky));
    }

    /** 전이 화면 ID → 그 화면에 놓인 필드 ID들. 화면을 전이마다 다시 읽지 않기 위한 인덱스다. */
    private Map<Long, List<String>> indexScreenFields() {
        Map<Long, List<String>> index = new HashMap<Long, List<String>>();
        FieldScreenManager screenManager = ComponentAccessor.getFieldScreenManager();
        for (FieldScreen screen : screenManager.getFieldScreens()) {
            if (screen == null || screen.getId() == null) {
                continue;
            }
            List<String> fieldIds = new ArrayList<String>();
            for (FieldScreenTab tab : screen.getTabs()) {
                for (FieldScreenLayoutItem item : tab.getFieldScreenLayoutItems()) {
                    if (item.getFieldId() != null && item.getFieldId().startsWith("customfield_")) {
                        fieldIds.add(item.getFieldId());
                    }
                }
            }
            index.put(screen.getId(), fieldIds);
        }
        return index;
    }

    /**
     * 이 전이가 쓰는 화면 ID. 화면이 없으면 null.
     *
     * <p><b>{@code getView()} 는 화면 ID 가 아니다.</b> 거기 들어 있는 것은 뷰 이름
     * ({@code resolveissue} / {@code commentassign} / {@code fieldscreen})이라
     * {@code Long.parseLong} 이 언제나 실패한다. v1.0.0~v1.4.1 은 그 실패를 "예외적인 경우"로
     * 보고 넘겨서 전이 화면 참조를 <b>한 건도</b> 잡지 못했다(실측 42번). 라벨까지 뒤집히지는
     * 않았다 — {@code ScreenCollector} 가 전이 전용 화면도 훑으므로 {@code SCREEN} 참조는
     * 남는다. 다만 관리자는 "어느 워크플로의 어느 전이가 그 화면을 쓰는지"를 통째로 못 봤고,
     * 전이 전용 화면은 프로젝트 체인이 비어 있어 "아무 데도 안 걸린 필드"처럼 읽혔다.
     *
     * <p>해석은 직접 하지 않고 <b>Jira 자신의 해석기</b>
     * {@link WorkflowActionsBean#getFieldScreenIdForView} 에 맡긴다. 전이 대화상자를 그릴 때
     * Jira 가 쓰는 바로 그 규칙이라, 우리가 세는 것과 Jira 가 보여주는 것이 어긋날 수 없다:
     * <ul>
     *   <li>뷰가 비어 있으면 화면 없음.</li>
     *   <li>옛 이름 {@code commentassign} → 2, {@code resolveissue} → 3. <b>메타보다 먼저</b>다.</li>
     *   <li>그 외 뷰({@code fieldscreen})는 메타 속성 {@code jira.fieldscreen.id} 를 읽는다.</li>
     *   <li>메타도 없으면 {@code IllegalArgumentException}, 메타가 숫자가 아니면
     *       {@code NumberFormatException} — 호출부가 그 전이 한 건만 "확인 불가"로 남긴다.
     *       Jira 도 그 전이의 화면을 못 그리므로 "화면 참조 0"이 맞다.</li>
     * </ul>
     * 처음엔 메타 → 숫자 뷰 → {@code getActionsForScreen} 세 경로를 직접 짰는데, 우선순위가
     * Jira 와 달랐고(메타를 먼저 봤다) 숫자 뷰는 Jira 가 받지도 않는 형식이었다(리뷰 지적).
     * 이 메서드는 {@code FieldScreenManager} 를 건드리지 않아 스캔 스레드에서 안전하고
     * 단위 테스트가 직접 부른다(그래서 package-private).
     */
    static Long screenIdOf(ActionDescriptor action) {
        if (action == null) {
            return null;
        }
        Optional<Long> id = new WorkflowActionsBean().getFieldScreenIdForView(action);
        return id.isPresent() ? id.get() : null;
    }

    /**
     * 워크플로 이름 → 이 워크플로가 실제로 적용되는 프로젝트 이름들.
     *
     * <p>프로젝트 × 이슈타입으로 실제 유효 워크플로를 물어본다. 워크플로 스킴에
     * 명시적 매핑이 없는 이슈타입은 스킴의 기본 워크플로로 떨어지는데, 스킴 쪽에서
     * 거꾸로 보면 이 경우를 놓친다.
     */
    private Map<String, Set<String>> buildProjectMap(ScanContext context) {
        Map<String, Set<String>> byWorkflow = new HashMap<String, Set<String>>();
        ProjectManager projectManager = ComponentAccessor.getProjectManager();
        WorkflowManager workflowManager = ComponentAccessor.getWorkflowManager();

        for (Project project : projectManager.getProjectObjects()) {
            try {
                for (IssueType issueType : project.getIssueTypes()) {
                    JiraWorkflow workflow = workflowManager.getWorkflow(project.getId(), issueType.getId());
                    if (workflow == null) {
                        continue;
                    }
                    Set<String> projects = byWorkflow.get(workflow.getName());
                    if (projects == null) {
                        projects = new LinkedHashSet<String>();
                        byWorkflow.put(workflow.getName(), projects);
                    }
                    projects.add(project.getName());
                }
            } catch (RuntimeException e) {
                context.addProblem("workflowChain", project.getKey(), e);
            }
        }
        return byWorkflow;
    }

    @Override
    public boolean isEssential() {
        return false;
    }
}
