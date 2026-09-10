package com.bskim.jira.janitor.fields.dao;

/**
 * {@code columnlayoutitem} 한 행 + 그 컬럼 설정의 주인(기획서 5.3(9)).
 *
 * <p>컬럼 설정은 셋 중 하나다. {@code columnlayout} 의 두 컬럼으로 구분한다.
 * <ul>
 *   <li>둘 다 비어 있으면 <b>시스템 기본</b> 컬럼 (모든 사용자의 출발점)</li>
 *   <li>{@code searchrequest} 가 있으면 <b>그 필터</b>의 컬럼</li>
 *   <li>{@code username} 이 있으면 <b>그 사용자</b>의 개인 컬럼</li>
 * </ul>
 *
 * <p><b>{@code username} 은 이름이 아니라 사용자 키다</b>(실측: {@code JIRAUSER10000}).
 * {@code searchrequest.authorname} 과 같다 — 화면에 그대로 내면 누구인지 알 수 없다.
 */
public final class ColumnLayoutRow {

    private final long layoutId;
    private final String userKey;
    private final Long filterId;
    private final String filterName;
    private final String fieldIdentifier;

    public ColumnLayoutRow(long layoutId, String userKey, Long filterId, String filterName,
                           String fieldIdentifier) {
        this.layoutId = layoutId;
        this.userKey = userKey;
        this.filterId = filterId;
        this.filterName = filterName;
        this.fieldIdentifier = fieldIdentifier;
    }

    public long getLayoutId() {
        return layoutId;
    }

    /** 개인 컬럼 설정의 주인. 사용자 <b>키</b>다. 시스템 기본·필터 컬럼이면 null. */
    public String getUserKey() {
        return userKey;
    }

    /** 이 컬럼 설정이 붙은 필터. 필터 컬럼이 아니면 null. */
    public Long getFilterId() {
        return filterId;
    }

    /** 필터 이름. 필터 행이 사라진 뒤 컬럼 설정만 남은 경우 null 일 수 있다. */
    public String getFilterName() {
        return filterName;
    }

    /** {@code customfield_10001} 형식. */
    public String getFieldIdentifier() {
        return fieldIdentifier;
    }

    /** 시스템 기본 컬럼인가. 개인·필터 어느 쪽에도 속하지 않으면 그렇다. */
    public boolean isSystemDefault() {
        return userKey == null && filterId == null;
    }
}
