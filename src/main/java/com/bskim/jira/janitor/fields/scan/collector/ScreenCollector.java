package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.util.I18nHelper;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 화면 참조 + 화면이 결국 어느 프로젝트에 닿는지의 체인 (기획서 5.3(1),(2)).
 *
 * <p>기본 관리 UI는 "이 화면에 이 필드가 있다"까지만 보여준다. 관리자가 실제로
 * 알고 싶은 것은 "이 필드가 결국 어느 프로젝트의 생성/편집 화면에 나타나는가"다.
 * 그래서 프로젝트에서 거꾸로 체인을 만든다:
 *
 * <pre>
 *   프로젝트 → 이슈타입 화면 스킴 → 화면 스킴 → (작업별) 화면
 * </pre>
 *
 * <p>프로젝트에서 출발하는 이유: 프로젝트 → ITSS 는 명시적 연결이 없으면 기본 스킴으로
 * 떨어진다. 스킴에서 프로젝트로 가는 방향만 보면 이 "기본 스킴에 얹혀 있는 프로젝트"를
 * 통째로 놓친다. 프로젝트 수는 화면 수보다 적어서 비용도 더 싸다.
 */
public class ScreenCollector implements ReferenceCollector {

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.SCREENS;
    }

    @Override
    public void collect(ScanContext context) {
        Map<Long, Set<String>> chainsByScreenId = buildProjectChains(context);

        FieldScreenManager screenManager = ComponentAccessor.getFieldScreenManager();
        for (FieldScreen screen : screenManager.getFieldScreens()) {
            try {
                collectScreen(context, screen, chainsByScreenId.get(screen.getId()));
            } catch (RuntimeException e) {
                context.addProblem("screen", screen.getName() + " (" + screen.getId() + ")", e);
            }
        }
    }

    private void collectScreen(ScanContext context, FieldScreen screen, Set<String> chains) {
        for (FieldScreenTab tab : screen.getTabs()) {
            for (FieldScreenLayoutItem item : tab.getFieldScreenLayoutItems()) {
                FieldUsage field = context.byFieldId(item.getFieldId());
                if (field == null) {
                    continue;
                }
                List<String> projects = chains == null
                        ? new ArrayList<String>()
                        : new ArrayList<String>(chains);
                context.addReference(field, new Reference(
                        ReferenceType.SCREEN,
                        screen.getName(),
                        String.valueOf(screen.getId()),
                        tab.getName(),
                        AdminUrls.screen(screen.getId()),
                        projects));
            }
        }
    }

    /**
     * 화면 ID → "프로젝트 (이슈타입 화면 스킴 › 화면 스킴 › 작업)" 문자열들.
     *
     * <p>이슈타입 화면 스킴 항목의 이슈타입까지 문자열에 넣지는 않는다. 항목이 많은
     * 인스턴스에서 줄이 폭발해 읽을 수 없게 되기 때문이다. 관리자가 더 깊이 봐야 하면
     * 링크로 실제 스킴 화면에 가면 된다.
     */
    private Map<Long, Set<String>> buildProjectChains(ScanContext context) {
        Map<Long, Set<String>> chains = new HashMap<Long, Set<String>>();

        ProjectManager projectManager = ComponentAccessor.getProjectManager();
        IssueTypeScreenSchemeManager itssManager =
                ComponentAccessor.getIssueTypeScreenSchemeManager();

        for (Project project : projectManager.getProjectObjects()) {
            try {
                IssueTypeScreenScheme itss = itssManager.getIssueTypeScreenScheme(project);
                if (itss == null) {
                    continue;
                }
                for (Object rawEntity : itss.getEntities()) {
                    IssueTypeScreenSchemeEntity entity = (IssueTypeScreenSchemeEntity) rawEntity;
                    FieldScreenScheme screenScheme = entity.getFieldScreenScheme();
                    if (screenScheme == null) {
                        continue;
                    }
                    for (FieldScreenSchemeItem item : screenScheme.getFieldScreenSchemeItems()) {
                        FieldScreen screen = item.getFieldScreen();
                        if (screen == null || screen.getId() == null) {
                            continue;
                        }
                        Set<String> forScreen = chains.get(screen.getId());
                        if (forScreen == null) {
                            forScreen = new LinkedHashSet<String>();
                            chains.put(screen.getId(), forScreen);
                        }
                        forScreen.add(project.getName()
                                + " (" + itss.getName()
                                + " › " + screenScheme.getName()
                                + " › " + operationName(item) + ")");
                    }
                }
            } catch (RuntimeException e) {
                context.addProblem("screenChain", project.getKey(), e);
            }
        }
        return chains;
    }

    /**
     * 작업 이름(생성/편집/조회). 항목에 작업이 지정되지 않으면 "default"다.
     *
     * <p>{@code getIssueOperationName()}은 스캔 스레드에서 빈 문자열을 준다(실측 —
     * 인증 컨텍스트가 없어서 i18n을 못 한다). 그대로 fallback하면 화면에
     * {@code admin.issue.operations.create} 같은 원시 키가 그대로 노출된다.
     * 그래서 로케일을 명시한 {@link I18nHelper.BeanFactory}로 직접 번역한다.
     * 스캔 시점에 문자열로 굳는 값이라 뷰어 로케일을 따를 수 없으므로 영어로 고정한다.
     */
    private String operationName(FieldScreenSchemeItem item) {
        try {
            if (item.getIssueOperation() == null) {
                return "default";
            }
            String name = item.getIssueOperationName();
            if (name != null && !name.trim().isEmpty() && !looksLikeI18nKey(name)) {
                return name;
            }
            return translate(item.getIssueOperation().getNameKey());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private String translate(String key) {
        if (key == null) {
            return "?";
        }
        try {
            I18nHelper.BeanFactory factory =
                    ComponentAccessor.getComponent(I18nHelper.BeanFactory.class);
            if (factory != null) {
                String text = factory.getInstance(Locale.ENGLISH).getText(key);
                if (text != null && !looksLikeI18nKey(text)) {
                    return text;
                }
            }
        } catch (RuntimeException e) {
            // 번역 실패는 표시 문제일 뿐이다. 키를 그대로 내는 것보다 마지막 조각이 낫다.
        }
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    /** i18n이 실패하면 키가 그대로 돌아온다. 점이 있고 공백이 없으면 키로 본다. */
    private static boolean looksLikeI18nKey(String text) {
        return text.indexOf('.') >= 0 && text.indexOf(' ') < 0;
    }
}
