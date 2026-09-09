package com.bskim.jira.janitor.fields.web;

import com.atlassian.plugin.PluginParseException;
import com.atlassian.plugin.web.Condition;

import java.util.Map;

/**
 * 관리 메뉴 항목을 시스템 관리자에게만 보여주는 condition.
 *
 * <p>Jira 기본 제공 {@code JiraGlobalPermissionCondition}을 쓰지 않는 이유:
 * 그 클래스는 생성자로 {@code GlobalPermissionManager}를 받고 플러그인의 Spring
 * 컨테이너에서 주입받으려 한다. 그러려면 {@code <component-import>}가 필요한데
 * {@code Atlassian-Plugin-Key}가 설정된 빌드에서는 XML의 component-import가
 * 금지된다(AMPS validate-manifest가 빌드를 막는다. 실측).
 *
 * <p>그래서 인수 없는 생성자로 직접 만들고 권한 판단은 {@link AdminGuard}에 맡긴다.
 * 액션/REST가 쓰는 것과 <b>같은</b> 판단을 쓰게 되는 이점도 있다.
 *
 * <p>이것은 메뉴 표시 여부일 뿐 접근 제어가 아니다. 실제 차단은 서버측 검사가 한다
 * (기획서 7.2 / 함정 1).
 */
public class AdminOnlyCondition implements Condition {

    @Override
    public void init(Map<String, String> params) throws PluginParseException {
        // 파라미터가 없다.
    }

    @Override
    public boolean shouldDisplay(Map<String, Object> context) {
        return AdminGuard.isAdmin();
    }
}
