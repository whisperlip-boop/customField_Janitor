package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 컨텍스트(적용 범위) 수집 (기획서 5.3(4)).
 *
 * <p>여기서 모으는 것은 상세 화면 상단의 "컨텍스트" 표시용이다. 컨텍스트는
 * "이 필드를 쓸 수 있는 범위"일 뿐 실제 사용의 증거가 아니므로, 상태 판정에서
 * 화면 참조와 같은 무게로 취급하지 않는다 — 참조 종류를 따로 둔 이유다.
 */
public class ContextCollector implements ReferenceCollector {

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.CONTEXTS;
    }

    @Override
    public void collect(ScanContext context) {
        CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager();

        for (FieldUsage field : context.getFields()) {
            try {
                if (!field.isTypeAvailable()) {
                    // 타입 제공 앱이 비활성이라 Jira API로 다룰 수 없는 필드다.
                    // 목록에 "타입 없음"으로 이미 표시되므로 여기서 또 경고하지 않는다.
                    continue;
                }
                CustomField customField = customFieldManager.getCustomFieldObject(field.getNumericId());
                if (customField == null) {
                    // 스캔 도중 필드가 지워졌다. 조용히 넘기지 않는다.
                    context.addProblem("context", field.getName() + " (" + field.getFieldId() + ")",
                            "스캔 중 필드를 찾을 수 없게 되었다");
                    continue;
                }
                collectField(context, field, customField);
            } catch (RuntimeException e) {
                context.addProblem("context", field.getName() + " (" + field.getFieldId() + ")", e);
            }
        }
    }

    private void collectField(ScanContext context, FieldUsage field, CustomField customField) {
        List<FieldConfigScheme> schemes = customField.getConfigurationSchemes();
        if (schemes == null) {
            return;
        }
        for (FieldConfigScheme scheme : schemes) {
            List<String> projects = new ArrayList<String>();
            if (scheme.isGlobal() || scheme.isAllProjects()) {
                field.setGlobalContext(true);
            } else {
                List<Project> associated = scheme.getAssociatedProjectObjects();
                if (associated != null) {
                    for (Project project : associated) {
                        projects.add(project.getName());
                        field.addContextProject(project.getName());
                    }
                }
            }

            List<String> issueTypes = new ArrayList<String>();
            if (!scheme.isAllIssueTypes()) {
                java.util.Collection<IssueType> associatedTypes = scheme.getAssociatedIssueTypes();
                if (associatedTypes != null) {
                    for (IssueType issueType : associatedTypes) {
                        if (issueType == null) {
                            continue;
                        }
                        issueTypes.add(issueType.getName());
                        field.addContextIssueType(issueType.getName());
                    }
                }
            }

            String detail = (scheme.isGlobal() || scheme.isAllProjects() ? "global projects" : "selected projects")
                    + " / "
                    + (scheme.isAllIssueTypes() ? "all issue types" : joinNames(issueTypes));

            context.addReference(field, new Reference(
                    ReferenceType.CONTEXT,
                    scheme.getName(),
                    String.valueOf(scheme.getId()),
                    detail,
                    AdminUrls.customFieldConfig(field.getNumericId()),
                    projects));
        }
    }

    private static String joinNames(List<String> names) {
        if (names.isEmpty()) {
            return "-";
        }
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(name);
        }
        return joined.toString();
    }
}
