package com.bskim.jira.janitor.fields.scan.collector;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 각 참조 대상의 Jira 관리 화면 경로. 기획서 결정 1: 이 앱은 지우지 않고,
 * 관리자를 해당 화면으로 데려다 준다. 그래서 링크 정확성이 곧 기능이다.
 *
 * <p>여기 있는 액션 alias는 Jira 8.13 {@code WEB-INF/classes/actions.xml}에서 실측했다.
 */
public final class AdminUrls {

    private AdminUrls() {
    }

    public static String screen(Long screenId) {
        return "/secure/admin/ConfigureFieldScreen.jspa?id=" + screenId;
    }

    public static String screenScheme(Long schemeId) {
        return "/secure/admin/ConfigureFieldScreenScheme.jspa?id=" + schemeId;
    }

    /** 기본 필드 설정은 id가 없다. 그때는 전역 필드 설정 화면으로 보낸다. */
    public static String fieldLayout(Long layoutId) {
        return layoutId == null
                ? "/secure/admin/ViewIssueFields.jspa"
                : "/secure/admin/ConfigureFieldLayout!default.jspa?id=" + layoutId;
    }

    public static String customFieldConfig(long customFieldId) {
        return "/secure/admin/ConfigureCustomField!default.jspa?customFieldId=" + customFieldId;
    }

    public static String workflow(String workflowName) {
        return "/secure/admin/workflows/ViewWorkflowSteps.jspa?workflowMode=live&workflowName="
                + encode(workflowName);
    }

    public static String filter(Long filterId) {
        return "/issues/?filter=" + filterId;
    }

    public static String permissionScheme(Long schemeId) {
        return "/secure/admin/EditPermissions!default.jspa?schemeId=" + schemeId;
    }

    public static String notificationScheme(Long schemeId) {
        return "/secure/admin/EditNotifications!default.jspa?schemeId=" + schemeId;
    }

    public static String issueSecurityScheme(Long schemeId) {
        return "/secure/admin/EditIssueSecurities!default.jspa?schemeId=" + schemeId;
    }

    public static String dashboard(Long dashboardId) {
        return dashboardId == null ? null : "/secure/Dashboard.jspa?selectPageId=" + dashboardId;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
