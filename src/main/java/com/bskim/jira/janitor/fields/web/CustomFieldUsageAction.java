package com.bskim.jira.janitor.fields.web;

import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.atlassian.sal.api.websudo.WebSudoRequired;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.model.ScanResult;
import com.bskim.jira.janitor.fields.scan.ScanFailure;
import com.bskim.jira.janitor.fields.scan.ScanService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code /secure/admin/CustomFieldUsage.jspa} — 목록 화면과 상세 화면.
 *
 * <p>{@code fieldId} 파라미터가 있으면 상세, 없으면 목록이다. 기획서 6.2의
 * "?fieldId=customfield_10001 로 어디서든 링크 가능하게" 요구를 그대로 만족한다.
 *
 * <p>URL을 {@code /secure/admin/} 아래에 두는 이유: 관리 화면 데코레이터가 붙고
 * {@code roles-required="admin"}으로 액션 진입이 막힌다.
 *
 * <p>{@code @WebSudoRequired}가 필요한 이유는 실측 때문이다. {@code /secure/admin/}
 * 경로에 있다고 Jira가 웹수도를 걸어주지는 않는다 — 애노테이션 없이 확인해보니
 * 로그인만 한 세션으로 이 화면이 그대로 열렸고, 같은 세션으로 Jira 기본
 * {@code ViewCustomFields.jspa}를 열면 "Administrator Access"를 요구했다.
 * 이 도구는 필드 구성·값 분포·필터 내용을 전부 노출하므로 Jira 기본 관리 화면과
 * 같은 수준을 지켜야 한다.
 *
 * <p>웹수도와 별개로 {@link AdminGuard}로 서버측 권한을 다시 검사한다
 * (기획서 7.2 / 함정 1). 웹수도는 "관리자가 지금 관리 작업 중임"을 확인하는 것이고
 * 권한 검사와는 다른 층이다.
 */
@WebSudoRequired
public class CustomFieldUsageAction extends JiraWebActionSupport {

    private final ScanService scanService = ScanService.getInstance();

    private String fieldId;
    private FieldUsage selectedField;
    /**
     * {@code fieldId} 를 숫자로 파싱한 값. 파싱 실패면 null.
     * URL을 다시 만들 때는 <b>이것만</b> 쓴다 — 원본 문자열을 쓰면 XSS가 된다.
     */
    private Long selectedFieldId;
    private String lang;
    private LocaleText locale;

    @Override
    protected String doExecute() {
        if (!AdminGuard.isAdmin(getLoggedInUser())) {
            return PERMISSION_VIOLATION_RESULT;
        }
        if (fieldId != null && !fieldId.trim().isEmpty()) {
            selectedField = findField(fieldId);
            if (selectedField == null) {
                // 스캔 전이거나 지워진 필드다. 목록으로 떨어뜨리고 안내를 낸다.
                addErrorMessage(text("janitor.fields.detail.notFound"));
                return SUCCESS;
            }
            return "detail";
        }
        return SUCCESS;
    }

    private FieldUsage findField(String rawId) {
        ScanResult result = scanService.getLastResult();
        if (result == null) {
            return null;
        }
        String value = rawId.trim();
        if (value.startsWith("customfield_")) {
            value = value.substring("customfield_".length());
        }
        try {
            Long numericId = Long.parseLong(value);
            FieldUsage found = result.getField(numericId);
            if (found != null) {
                // 파싱에 성공하고 실제로 존재하는 필드일 때만 보관한다.
                selectedFieldId = numericId;
            }
            return found;
        } catch (NumberFormatException e) {
            return null;
        }
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

    /**
     * 선택된 언어로 문구를 얻는다. 이 화면은 {@code getText()} 대신 이것만 쓴다 —
     * 절반은 보는 사람 로케일, 절반은 선택한 언어로 나오면 안 된다.
     */
    /**
     * 인자를 넣는 문구. 이게 없으면 Velocity가 2-arg 호출의 메서드를 못 찾아
     * {@code $action.text("...", $field.name)} 를 <b>문자열 그대로</b> 렌더링한다
     * (실측: 상세 화면 {@code <title>}).
     */
    public String text(String key, Object arg) {
        return locale().text(key, arg);
    }

    public String text(String key) {
        return locale().text(key);
    }

    public String getLang() {
        return locale().getLang();
    }

    public boolean isKorean() {
        return locale().isKorean();
    }

    /** 언어를 유지하며 이 화면으로 돌아오는 링크. */
    public String getSelfUrl() {
        return "CustomFieldUsage.jspa" + locale().queryParam(true);
    }

    /**
     * 언어 토글 링크. <b>요청받은 {@code fieldId} 문자열을 그대로 붙이지 않는다.</b>
     *
     * <p>그렇게 하면 반사형 XSS가 된다. {@code fieldId} 가 숫자로 파싱되지 않으면
     * {@code doExecute} 는 목록 템플릿으로 떨어지는데, 그 템플릿의 토글은
     * {@code <a href="$action.koUrl">} 로 값을 그대로 심는다. 즉
     * {@code ?fieldId="><img src=x onerror=...>} 가 href를 탈출한다.
     *
     * <p>그래서 파싱에 성공한 숫자만 다시 내보낸다. 유효하지 않은 값을 URL에
     * 되돌려 줄 이유가 애초에 없다.
     */
    public String getKoUrl() {
        return langUrl("ko");
    }

    public String getEnUrl() {
        return langUrl("en");
    }

    private String langUrl(String lang) {
        return "CustomFieldUsage.jspa?lang=" + lang
                + (selectedFieldId == null ? "" : "&fieldId=customfield_" + selectedFieldId);
    }

    public String getHelpUrl() {
        return "CustomFieldUsageHelp.jspa" + locale().queryParam(true);
    }

    /** 상세 화면으로 가는 링크. 선택한 언어를 유지한다. */
    public String detailUrl(FieldUsage field) {
        return "CustomFieldUsage.jspa?fieldId=" + field.getFieldId() + locale().queryParam(false);
    }

    /** 진행률 폴링이 화면과 같은 언어로 답하게 하려고 REST에도 언어를 넘긴다. */
    public String getRestLangParam() {
        return locale().queryParam(true);
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String getFieldId() {
        return fieldId;
    }

    public FieldUsage getSelectedField() {
        return selectedField;
    }

    // --- 템플릿이 쓰는 조회 메서드들 -------------------------------------------------

    public boolean isScanned() {
        return scanService.getLastResult() != null;
    }

    public boolean isScanning() {
        return scanService.isRunning();
    }

    /**
     * 마지막 스캔이 실패했으면 그 메시지, 아니면 null. 화면에 그대로 낸다.
     *
     * <p>진행률만으로는 부족하다 — 실패 후 화면을 새로 그리면 진행률은 사라지고
     * <b>이전 스캔의 표와 시각만 남는다.</b> 그러면 관리자는 방금 스캔한 결과를
     * 보고 있다고 믿는데 실제로는 지난번 스냅샷이다(기획서 7).
     */
    public String getScanFailure() {
        ScanFailure failure = scanService.getLastFailure();
        return failure == null ? null : failure.getMessage();
    }

    public String getScanFailureAt() {
        ScanFailure failure = scanService.getLastFailure();
        return failure == null ? null : formatLocal(failure.getFinishedAt());
    }

    /**
     * 화면에 보이는 표가 실패한 스캔보다 오래된 것인가. 참이면 "지금 보는 것은
     * 낡은 결과"라고 명시해야 한다 — 이게 이 수정의 핵심이다.
     */
    public boolean isResultStale() {
        ScanFailure failure = scanService.getLastFailure();
        ScanResult result = scanService.getLastResult();
        return failure != null && result != null
                && failure.getFinishedAt().after(result.getFinishedAt());
    }

    public List<FieldUsage> getFields() {
        ScanResult result = scanService.getLastResult();
        return result == null ? Collections.<FieldUsage>emptyList() : result.getFields();
    }

    /**
     * Type 필터 드롭다운에 넣을 타입 이름 목록. 스캔 결과에 실제로 존재하는 타입만
     * 낸다 — 설치되지 않은 타입을 목록에 두면 고를 수는 있는데 결과가 0건이라
     * 필터가 고장난 것처럼 보인다.
     *
     * <p>타입 이름이 없는 필드(제공 앱이 비활성)는 타입 키가 이름 자리에 들어가므로
     * 그대로 목록에 나온다. 그게 맞다 — 관리자가 그 필드들만 골라 보고 싶어 한다.
     */
    public List<String> getFieldTypes() {
        Set<String> types = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (FieldUsage field : getFields()) {
            if (field.getTypeName() != null && !field.getTypeName().trim().isEmpty()) {
                types.add(field.getTypeName());
            }
        }
        return new ArrayList<String>(types);
    }

    public List<ScanProblem> getProblems() {
        ScanResult result = scanService.getLastResult();
        return result == null ? Collections.<ScanProblem>emptyList() : result.getProblems();
    }

    public String getLastScanAt() {
        ScanResult result = scanService.getLastResult();
        return result == null ? null : formatLocal(result.getFinishedAt());
    }

    public long getLastScanDurationMs() {
        ScanResult result = scanService.getLastResult();
        return result == null ? 0L : result.getDurationMs();
    }

    public ScanProgress getProgress() {
        return scanService.getProgress();
    }

    /** 상세 화면의 카테고리별 트리. 종류마다 참조 목록을 꺼낸다(기획서 6.2). */
    public List<Reference> referencesOfType(String typeCode) {
        if (selectedField == null) {
            return Collections.emptyList();
        }
        for (ReferenceType type : ReferenceType.values()) {
            if (type.getCode().equals(typeCode)) {
                return selectedField.getReferences(type);
            }
        }
        return Collections.emptyList();
    }

    public List<ReferenceType> getReferenceTypes() {
        return new ArrayList<ReferenceType>(java.util.Arrays.asList(ReferenceType.values()));
    }

    public String formatLocal(Date date) {
        if (date == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(date);
    }

    public String formatDay(Date date) {
        if (date == null) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(date);
    }

    /**
     * HTML 이스케이프. 필드 이름·필터 이름·JQL 발췌 등은 전부 사용자 입력이므로
     * 템플릿에서 그냥 뿌리면 관리 화면에 스크립트가 들어온다.
     */
    public String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&#39;");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** 목록/CSV 링크에서 쓰는 REST 베이스. 컨텍스트 경로가 있는 설치본을 위해 함께 낸다. */
    public String getRestBase() {
        return getHttpRequest().getContextPath() + "/rest/janitor/1.0";
    }

    public String getContextPath() {
        return getHttpRequest().getContextPath();
    }
}
