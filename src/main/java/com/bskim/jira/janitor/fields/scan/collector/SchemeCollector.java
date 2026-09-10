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
            context.addProblem(area, "-", "janitor.fields.problem.schemeManager");
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
                                "janitor.fields.problem.schemeTargetMissing",
                                String.valueOf(entity.getParameter()));
                        continue;
                    }
                    // entityTypeId 를 targetId 에 함께 넣는다. 이게 없으면 한 스킴이
                    // 같은 필드로 Browse / Edit / Assign 세 권한을 줄 때 세 참조의
                    // 동일성 키가 완전히 같아져서, addReference 의 중복 제거가 3건을
                    // 1건으로 합친다(v1.0.1 에서 실제로 그랬다 — 중복 제거를 넣으면서
                    // 만든 회귀다). [위험] 라벨이 붙은 필드의 상세 화면은 관리자가
                    // "무엇을 손봐야 하는가"를 읽는 유일한 곳이라, 세 군데가 한 줄로
                    // 뭉개지면 그 화면의 목적이 사라진다.
                    //
                    // 권한/이벤트 이름으로 풀어 보여주는 것은 아직 안 한다 — 여기서
                    // 필요한 것은 "서로 다른 참조다"를 성립시키는 식별자다.
                    Object entityTypeId = entity.getEntityTypeId();
                    String targetId = entityTypeId == null
                            ? String.valueOf(scheme.getId())
                            : scheme.getId() + "/" + entityTypeId;
                    context.addReference(field, new Reference(
                            referenceType,
                            scheme.getName(),
                            targetId,
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
