package com.bskim.jira.janitor.fields.web;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.user.ApplicationUser;

/**
 * 서버측 권한 검사. 기획서 7.2 / 함정 1.
 *
 * <p>web-item의 condition은 <b>메뉴를 숨길 뿐</b> URL 직접 호출을 막지 못한다.
 * 이 도구는 필드 구성, 값 분포, 필터 내용 같은 인스턴스 내부 정보를 전부 노출하므로
 * 모든 진입점(액션 / REST / CSV)에서 이 검사를 다시 한다.
 *
 * <p>거부할 때 이유를 본문에 담지 않는다 — 권한 없는 호출자에게 무엇이 있는지
 * 알려줄 이유가 없다.
 */
public final class AdminGuard {

    private AdminGuard() {
    }

    public static boolean isAdmin() {
        return isAdmin(ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser());
    }

    public static boolean isAdmin(ApplicationUser user) {
        if (user == null) {
            return false;
        }
        GlobalPermissionManager permissionManager = ComponentAccessor.getGlobalPermissionManager();
        return permissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user);
    }
}
