package com.bskim.jira.janitor.fields.web;

import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.atlassian.sal.api.websudo.WebSudoRequired;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.UsageStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /secure/admin/CustomFieldUsageHelp.jspa} — 사용 설명서.
 *
 * <p>목록 화면의 안내 문구 아래 링크로 들어온다. 이 도구는 라벨 하나로 판단을
 * 요약해 보여주는데, 그 라벨이 무엇을 근거로 붙었는지 모르면 관리자가 숫자를
 * 잘못 읽는다. 특히 [고아 데이터]와 [방치]는 겉보기가 비슷하고 조치가 정반대다.
 *
 * <p>화면에 상태가 없다 — 렌더링만 한다. 그래도 권한 검사는 한다: 설명서 자체에
 * 민감한 정보는 없지만, 목록/상세와 접근 조건이 다르면 그게 혼란의 원인이 된다.
 */
@WebSudoRequired
public class CustomFieldUsageHelpAction extends JiraWebActionSupport {

    private String lang;
    private LocaleText locale;

    @Override
    protected String doExecute() {
        if (!AdminGuard.isAdmin(getLoggedInUser())) {
            return PERMISSION_VIOLATION_RESULT;
        }
        return SUCCESS;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    private LocaleText locale() {
        if (locale == null) {
            locale = new LocaleText(lang, getLocale());
        }
        return locale;
    }

    /** 선택된 언어로 문구를 얻는다. 이 화면은 이것만 쓴다({@link LocaleText} 참고). */
    public String text(String key) {
        return locale().text(key);
    }

    public boolean isKorean() {
        return locale().isKorean();
    }

    public String getKoUrl() {
        return "CustomFieldUsageHelp.jspa?lang=ko";
    }

    public String getEnUrl() {
        return "CustomFieldUsageHelp.jspa?lang=en";
    }

    /** 목록 화면으로 돌아가는 링크. 선택한 언어를 유지한다. */
    public String getListUrl() {
        return "CustomFieldUsage.jspa" + locale().queryParam(true);
    }

    /** 목록 화면으로 돌아가는 링크. 컨텍스트 경로가 있는 설치본을 위해 함께 낸다. */
    public String getContextPath() {
        return getHttpRequest().getContextPath();
    }

    /** 설명서 본문의 참조 카테고리 표를 열거형에서 만든다. */
    public List<ReferenceType> getReferenceTypes() {
        return Arrays.asList(ReferenceType.values());
    }

    /** 상태 라벨 5개를 판정 우선순위 역순(청소 대상이 먼저)으로 낸다. */
    public List<UsageStatus> getStatusesByCleanupOrder() {
        List<UsageStatus> all = new ArrayList<UsageStatus>(Arrays.asList(UsageStatus.values()));
        all.sort(new Comparator<UsageStatus>() {
            @Override
            public int compare(UsageStatus left, UsageStatus right) {
                return left.getCleanupOrder() - right.getCleanupOrder();
            }
        });
        return all;
    }
}
