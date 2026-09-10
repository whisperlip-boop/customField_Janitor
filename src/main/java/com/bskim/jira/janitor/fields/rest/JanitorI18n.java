package com.bskim.jira.janitor.fields.rest;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.util.I18nHelper;
import com.bskim.jira.janitor.fields.web.LocaleText;

/**
 * 호출자 로케일로 i18n 문구를 얻는다. 요청 스레드에서만 쓴다
 * (스캔 스레드에는 인증 컨텍스트가 없다).
 */
public final class JanitorI18n {

    private JanitorI18n() {
    }

    /** 호출자 로케일의 {@code I18nHelper}. 못 얻으면 null — 부르는 쪽이 대체 문자열을 낸다. */
    public static I18nHelper helper() {
        return ComponentAccessor.getJiraAuthenticationContext().getI18nHelper();
    }

    public static String text(String key) {
        I18nHelper i18n = ComponentAccessor.getJiraAuthenticationContext().getI18nHelper();
        return i18n == null ? key : i18n.getText(key);
    }

    /**
     * 요청된 언어로 문구를 얻는다. 화면이 {@code ?lang=ko} 로 열려 있는데 진행률
     * 문구만 보는 사람 로케일로 나오면 반쪽만 번역된 화면이 된다. {@code lang}이
     * 비어 있으면 보는 사람 로케일을 쓴다(기존 동작).
     */
    public static String text(String key, String lang) {
        if (lang == null || lang.trim().isEmpty()) {
            return text(key);
        }
        I18nHelper i18n = LocaleText.resolve(LocaleText.toLocale(lang));
        return i18n == null ? text(key) : i18n.getText(key);
    }

    public static String text(String key, Object... params) {
        I18nHelper i18n = ComponentAccessor.getJiraAuthenticationContext().getI18nHelper();
        if (i18n == null) {
            return key;
        }
        String[] values = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            values[i] = params[i] == null ? "" : String.valueOf(params[i]);
        }
        return i18n.getText(key, values);
    }
}
