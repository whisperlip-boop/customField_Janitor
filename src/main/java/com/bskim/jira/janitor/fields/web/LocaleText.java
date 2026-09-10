package com.bskim.jira.janitor.fields.web;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.util.I18nHelper;

import java.util.Locale;

/**
 * 화면이 보여줄 언어를 결정하고 그 언어로 문구를 얻는다.
 *
 * <p>이 앱의 화면들은 {@code ?lang=ko} / {@code ?lang=en} 으로 표시 언어를 고정할 수
 * 있다. 파라미터가 없으면 보는 사람의 Jira 로케일을 따른다.
 *
 * <p>언어를 URL 파라미터로 두는 이유: "한국어로 열리는 링크"를 사내에 그대로 공유할 수
 * 있다. JS로 두 언어를 동시에 렌더링하고 감추는 방식은 DOM이 두 배가 되고 링크 공유가
 * 안 된다.
 *
 * <p>세 화면(목록·상세·설명서)이 같은 규칙을 써야 하므로 여기 한 곳에 모았다. 화면마다
 * 따로 구현하면 한쪽만 고쳐서 절반은 한국어, 절반은 영어로 나오는 상태가 생긴다.
 */
public final class LocaleText {

    public static final String KO = "ko";
    public static final String EN = "en";

    private final String lang;
    private I18nHelper i18n;

    /**
     * @param requested {@code lang} 파라미터 값. 비어 있으면 {@code viewerLocale}을 쓴다.
     * @param viewerLocale 보는 사람의 Jira 로케일. null이면 영어로 본다.
     */
    public LocaleText(String requested, Locale viewerLocale) {
        if (requested != null && !requested.trim().isEmpty()) {
            this.lang = KO.equalsIgnoreCase(requested.trim()) ? KO : EN;
        } else {
            this.lang = viewerLocale != null && KO.equals(viewerLocale.getLanguage()) ? KO : EN;
        }
    }

    public String getLang() {
        return lang;
    }

    public boolean isKorean() {
        return KO.equals(lang);
    }

    /** 이 언어로 문구를 얻는다. 실패하면 키를 그대로 낸다 — 빈 화면보다 낫다. */
    public String text(String key) {
        return i18n() == null ? key : i18n().getText(key);
    }

    /**
     * 인자를 넣는 문구. {@code {0}} 자리에 들어간다.
     *
     * <p>이 오버로드가 없으면 Velocity가 2-arg 호출의 메서드를 찾지 못해
     * {@code $action.text("key", $arg)} 를 <b>문자열 그대로</b> 출력한다.
     */
    public String text(String key, Object arg) {
        return i18n() == null ? key : i18n().getText(key, arg);
    }

    /**
     * 인자 셋짜리 문구.
     *
     * <p>오버로드가 없는 인자 수로 부르면 Velocity 는 <b>에러를 내지 않고</b>
     * {@code $action.text("...", $a, $b, $c)} 를 문자열 그대로 렌더한다(docs/00 24번).
     * 그래서 화면이 쓰는 인자 수만큼 여기에 오버로드가 있어야 한다.
     *
     * <p>{@code I18nHelper} 에는 2-arg 오버로드가 없다(1, 3, 4 … 순이다). 실측한 사실이라
     * 인자 둘짜리 문구가 필요하면 {@code null} 을 하나 채워 3-arg 로 부를 것.
     */
    public String text(String key, Object first, Object second, Object third) {
        return i18n() == null ? key : i18n().getText(key, first, second, third);
    }

    /** 이 언어의 {@code I18nHelper}. 문구를 직접 조립하는 쪽(예: ScanProblem)이 쓴다. */
    public I18nHelper helper() {
        return i18n();
    }

    private I18nHelper i18n() {
        if (i18n == null) {
            i18n = resolve(isKorean() ? Locale.KOREAN : Locale.ENGLISH);
        }
        return i18n;
    }

    /** 지금 보고 있지 않은 쪽의 언어 코드. 토글 링크에 쓴다. */
    public String getOtherLang() {
        return isKorean() ? EN : KO;
    }

    /**
     * 링크에 붙일 {@code lang} 파라미터. 페이지를 오갈 때 선택한 언어가 유지되어야 한다.
     *
     * @param first 이 파라미터가 URL의 첫 파라미터면 true
     */
    public String queryParam(boolean first) {
        return (first ? "?" : "&") + "lang=" + lang;
    }

    /** 지정한 로케일의 i18n 도우미. REST 가 화면과 같은 언어로 답할 때도 쓴다. */
    public static I18nHelper resolve(Locale locale) {
        try {
            I18nHelper.BeanFactory factory = ComponentAccessor.getComponent(I18nHelper.BeanFactory.class);
            return factory == null ? null : factory.getInstance(locale);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code lang} 문자열을 로케일로 바꾼다. REST가 화면과 같은 언어로 답하게 할 때 쓴다. */
    public static Locale toLocale(String requested) {
        return requested != null && KO.equalsIgnoreCase(requested.trim()) ? Locale.KOREAN : Locale.ENGLISH;
    }
}
