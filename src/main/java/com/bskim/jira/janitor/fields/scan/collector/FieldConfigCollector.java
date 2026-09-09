package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
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
import java.util.Map;
import java.util.Set;

/**
 * 필드 설정(표시/숨김/필수) 참조 (기획서 5.3(3)).
 *
 * <p>"이 필드는 X 프로젝트에서 필수"는 삭제 판단을 바꾸는 정보다. 필수인 필드를
 * 지우면 그 프로젝트의 이슈 생성이 막힌다. 그래서 표시/숨김/필수를 상세에 남긴다.
 *
 * <p>프로젝트 역방향은 화면과 같은 이유로 프로젝트에서 출발한다
 * ({@link ScreenCollector} 참고).
 */
public class FieldConfigCollector implements ReferenceCollector {

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.FIELD_CONFIGS;
    }

    @Override
    public void collect(ScanContext context) {
        FieldLayoutManager layoutManager = ComponentAccessor.getFieldLayoutManager();
        Map<Long, Set<String>> projectsByLayoutId = buildProjectMap(context, layoutManager);

        for (EditableFieldLayout layout : layoutManager.getEditableFieldLayouts()) {
            try {
                collectLayout(context, layout, projectsByLayoutId.get(layout.getId()));
            } catch (RuntimeException e) {
                context.addProblem("fieldConfig", layout.getName() + " (" + layout.getId() + ")", e);
            }
        }
    }

    private void collectLayout(ScanContext context, FieldLayout layout, Set<String> projects) {
        for (FieldLayoutItem item : layout.getFieldLayoutItems()) {
            if (item.getOrderableField() == null) {
                continue;
            }
            FieldUsage field = context.byFieldId(item.getOrderableField().getId());
            if (field == null) {
                continue;
            }
            // 숨김/필수 여부가 이 참조의 요점이다. 숨김이어도 참조는 참조다 —
            // 필드 설정에 걸려 있다는 사실 자체가 "아직 어딘가에 묶여 있다"는 신호다.
            List<String> flags = new ArrayList<String>();
            if (item.isHidden()) {
                flags.add("hidden");
            }
            if (item.isRequired()) {
                flags.add("required");
            }
            if (flags.isEmpty()) {
                flags.add("visible");
            }

            context.addReference(field, new Reference(
                    ReferenceType.FIELD_CONFIG,
                    layout.getName(),
                    layout.getId() == null ? "default" : String.valueOf(layout.getId()),
                    join(flags),
                    AdminUrls.fieldLayout(layout.getId()),
                    projects == null ? null : new ArrayList<String>(projects)));
        }
    }

    private Map<Long, Set<String>> buildProjectMap(ScanContext context, FieldLayoutManager layoutManager) {
        Map<Long, Set<String>> projectsByLayoutId = new HashMap<Long, Set<String>>();
        ProjectManager projectManager = ComponentAccessor.getProjectManager();

        for (Project project : projectManager.getProjectObjects()) {
            try {
                for (FieldLayout layout : layoutManager.getUniqueFieldLayouts(project)) {
                    Long key = layout.getId();
                    Set<String> projects = projectsByLayoutId.get(key);
                    if (projects == null) {
                        projects = new LinkedHashSet<String>();
                        projectsByLayoutId.put(key, projects);
                    }
                    projects.add(project.getName());
                }
            } catch (RuntimeException e) {
                context.addProblem("fieldConfigChain", project.getKey(), e);
            }
        }
        return projectsByLayoutId;
    }

    private static String join(List<String> parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(part);
        }
        return joined.toString();
    }

    @Override
    public boolean isEssential() {
        return false;
    }
}
