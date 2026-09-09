package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.security.IssueSecuritySchemeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.scheme.Scheme;
import com.atlassian.jira.scheme.SchemeEntity;
import com.atlassian.jira.scheme.SchemeManager;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 권한 / 알림 / 이슈 보안 스킴 참조 (기획서 5.3(7)). 여기가 "조용히 깨지는" 곳이다.
 *
 * <p>타입 문자열은 실측했다 (docker Jira 8.13):
 * <ul>
 *   <li>{@code WEB-INF/classes/permission-types.xml} → {@code userCF}, {@code groupCF}
 *       (권한 스킴과 이슈 보안 스킴이 함께 쓴다)</li>
 *   <li>{@code WEB-INF/classes/notification-event-types.xml} →
 *       {@code User_Custom_Field_Value}, {@code Group_Custom_Field_Value}</li>
 * </ul>
 *
 * <p>이슈 보안 스킴을 함께 훑는 이유: 기획서는 권한·알림만 적었지만 {@code userCF}는
 * 이슈 보안 스킴에도 똑같이 쓰이고, 인터페이스가 같아서 추가 비용이 없다.
 * 놓치면 위험도는 권한 스킴과 동일하다(이슈가 안 보이게 된다).
 *
 * <p>parameter 값의 형식(숫자 ID인지 {@code customfield_ID}인지)은 버전/타입에 따라
 * 다를 수 있어 <b>양쪽 다</b> 받아들인다.
 */
public class SchemeCollector implements ReferenceCollector {

    private static final Set<String> PERMISSION_CF_TYPES =
            new HashSet<String>(Arrays.asList("userCF", "groupCF"));

    private static final Set<String> NOTIFICATION_CF_TYPES =
            new HashSet<String>(Arrays.asList("User_Custom_Field_Value", "Group_Custom_Field_Value"));

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.SCHEMES;
    }

    @Override
    public void collect(ScanContext context) {
        collectScheme(context, ComponentAccessor.getPermissionSchemeManager(),
                PERMISSION_CF_TYPES, ReferenceType.PERMISSION_SCHEME, "permissionScheme");

        collectScheme(context, ComponentAccessor.getNotificationSchemeManager(),
                NOTIFICATION_CF_TYPES, ReferenceType.NOTIFICATION_SCHEME, "notificationScheme");

        collectScheme(context, ComponentAccessor.getComponent(IssueSecuritySchemeManager.class),
                PERMISSION_CF_TYPES, ReferenceType.ISSUE_SECURITY_SCHEME, "issueSecurityScheme");
    }

    private void collectScheme(ScanContext context, SchemeManager manager, Set<String> cfTypes,
                               ReferenceType referenceType, String area) {
        if (manager == null) {
            context.addProblem(area, "-", "스킴 매니저를 얻지 못했다");
            return;
        }
        List<Scheme> schemes;
        try {
            schemes = manager.getSchemeObjects();
        } catch (RuntimeException e) {
            context.addProblem(area, "-", e);
            return;
        }

        for (Scheme scheme : schemes) {
            try {
                List<String> projects = projectNames(manager, scheme);
                Collection<SchemeEntity> entities = scheme.getEntities();
                if (entities == null) {
                    continue;
                }
                for (SchemeEntity entity : entities) {
                    if (entity.getType() == null || !cfTypes.contains(entity.getType())) {
                        continue;
                    }
                    FieldUsage field = resolve(context, entity.getParameter());
                    if (field == null) {
                        // 참조는 있는데 가리키는 필드를 못 찾았다. 이미 지워진 필드일 수 있다.
                        // 우리 목록에는 낼 자리가 없으니 확인 불가로 남긴다.
                        context.addProblem(area, scheme.getName() + " / " + entity.getType(),
                                "참조 대상 필드를 찾지 못했다: parameter=" + entity.getParameter());
                        continue;
                    }
                    context.addReference(field, new Reference(
                            referenceType,
                            scheme.getName(),
                            String.valueOf(scheme.getId()),
                            entity.getType(),
                            adminUrl(referenceType, scheme.getId()),
                            projects));
                }
            } catch (RuntimeException e) {
                context.addProblem(area, scheme.getName() + " (" + scheme.getId() + ")", e);
            }
        }
    }

    private FieldUsage resolve(ScanContext context, String parameter) {
        if (parameter == null || parameter.trim().isEmpty()) {
            return null;
        }
        String value = parameter.trim();
        FieldUsage byFieldId = context.byFieldId(value);
        if (byFieldId != null) {
            return byFieldId;
        }
        try {
            return context.byNumericId(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> projectNames(SchemeManager manager, Scheme scheme) {
        List<String> names = new ArrayList<String>();
        try {
            List<Project> projects = manager.getProjects(scheme);
            if (projects != null) {
                for (Project project : projects) {
                    names.add(project.getName());
                }
            }
        } catch (RuntimeException e) {
            // 프로젝트 목록은 부가 정보다. 실패하면 참조 자체는 살린다.
            names.clear();
        }
        return names;
    }

    private String adminUrl(ReferenceType type, Long schemeId) {
        switch (type) {
            case PERMISSION_SCHEME:
                return AdminUrls.permissionScheme(schemeId);
            case NOTIFICATION_SCHEME:
                return AdminUrls.notificationScheme(schemeId);
            case ISSUE_SECURITY_SCHEME:
                return AdminUrls.issueSecurityScheme(schemeId);
            default:
                return null;
        }
    }

    @Override
    public boolean isEssential() {
        return false;
    }
}
