package com.bskim.jira.janitor.fields.model;

import com.atlassian.jira.util.I18nHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 스캔 중 확인하지 못한 항목 한 건. 기획서 함정 7: 실패를 조용히 누락시키면
 * 도구를 신뢰할 수 없게 된다. 실패는 반드시 "확인 불가" 목록으로 노출한다.
 *
 * <p><b>사람이 읽는 문장은 여기에 완성된 채로 담지 않는다.</b> i18n 키와 인자만 담고
 * 화면이 {@link #resolve(I18nHelper)} 로 그린다. 이유는 참조의 {@code detailI18nKey} 와 같다:
 * 스캔 스레드에는 보는 사람의 로케일이 없고(docs/00 함정), 스캔 결과 한 벌을 한국어로 보는
 * 사람과 영어로 보는 사람이 함께 본다 — 스캔 시점에 언어를 고를 수가 없다. v1.4.0 까지는
 * 이 자리만 그 처리를 못 받아, 영어 화면의 "이유" 칸에 한글이 그대로 나왔다(docs/00 41번).
 *
 * <p>예외에서 나온 텍스트({@code JqlParseException: ...})는 번역 대상이 아니라
 * {@link #raw} 로 그대로 담는다.
 */
public final class ScanProblem {

    private final String area;
    private final String target;
    /** i18n 키. null 이면 {@link #message} 가 이미 완성된 텍스트다. */
    private final String messageKey;
    /** {@code {0}} 자리에 들어갈 값들. 키가 있을 때만 의미가 있다. */
    private final List<String> messageArgs;
    /** 언어 중립 텍스트(예외 이름 등) 또는 i18n 을 못 얻었을 때의 대체 문자열. */
    private final String message;

    private ScanProblem(String area, String target, String messageKey, List<String> messageArgs,
                        String message) {
        this.area = area;
        this.target = target;
        this.messageKey = messageKey;
        this.messageArgs = Collections.unmodifiableList(
                messageArgs == null ? new ArrayList<String>() : new ArrayList<String>(messageArgs));
        this.message = message;
    }

    /**
     * 번역할 문장. 화면이 그릴 때 언어가 정해진다.
     *
     * <p>인자는 <b>하나까지만</b> 쓴다 — {@code I18nHelper} 에는 2-arg 오버로드가 없다
     * (1, 3, 4 … 순이다. docs/00 24번).
     */
    public static ScanProblem keyed(String area, String target, String messageKey, String... args) {
        return new ScanProblem(area, target, messageKey, Arrays.asList(args), messageKey);
    }

    /**
     * 번역하지 않는 텍스트. 예외 클래스 이름·DB 메시지처럼 <b>어느 언어로도 그대로인</b>
     * 것에만 쓴다. 사람이 읽는 문장을 여기 넣으면 v1.4.0 이전의 결함이 되살아난다.
     */
    public static ScanProblem raw(String area, String target, String message) {
        return new ScanProblem(area, target, null, null, message);
    }

    /** 스냅샷 복원용. 키가 없는 옛 스냅샷은 {@code message} 만 들고 온다. */
    public static ScanProblem restored(String area, String target, String messageKey,
                                       List<String> messageArgs, String message) {
        return new ScanProblem(area, target, messageKey, messageArgs, message);
    }

    /** 어느 수집 단계에서 났는지. 예: "filter", "workflow". 식별자라 번역하지 않는다. */
    public String getArea() {
        return area;
    }

    /** 무엇을 확인하지 못했는지. 예: 필터 이름 + ID. */
    public String getTarget() {
        return target;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public List<String> getMessageArgs() {
        return messageArgs;
    }

    /**
     * 저장·전송용 원본. <b>화면에는 이걸 쓰지 말고</b> {@link #resolve(I18nHelper)} 를 쓴다 —
     * 키가 있는 항목은 여기에 키가 들어 있다.
     */
    public String getMessage() {
        return message;
    }

    /** 보는 사람의 언어로 그린다. i18n 을 못 얻으면 대체 문자열(키 또는 예외 텍스트)을 낸다. */
    public String resolve(I18nHelper i18n) {
        if (messageKey == null || i18n == null) {
            return message;
        }
        if (messageArgs.isEmpty()) {
            return i18n.getText(messageKey);
        }
        if (messageArgs.size() == 1) {
            return i18n.getText(messageKey, messageArgs.get(0));
        }
        // 2-arg 오버로드가 없어서 셋으로 맞춘다(빈 칸은 빈 문자열).
        return i18n.getText(messageKey, messageArgs.get(0), messageArgs.get(1),
                messageArgs.size() > 2 ? messageArgs.get(2) : "");
    }
}
