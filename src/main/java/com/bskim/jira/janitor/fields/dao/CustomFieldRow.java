package com.bskim.jira.janitor.fields.dao;

/**
 * {@code customfield} 테이블 한 행. 커스텀 필드의 <b>존재</b>에 대한 유일한 사실이다.
 *
 * <p>{@code CustomFieldManager}를 쓰지 않고 이 테이블을 함께 읽는 이유는 실측 때문이다.
 * {@code CustomFieldManager.getCustomFieldObjects()}는 <b>타입을 제공하는 앱이 살아 있는</b>
 * 필드만 돌려준다. docker Jira에서 필드 11개 중 5개만 나왔고, 빠진 6개는 Jira Software
 * (greenhopper)가 제공하는 Epic Link / Sprint / Rank 등이었다.
 *
 * <p>정리 도구 입장에서 그 6개는 오히려 가장 중요한 후보다 — 앱을 지웠는데 필드와 값만
 * 남아 있는 상태가 정확히 청소 대상이다. 목록에서 조용히 사라지면 관리자는 그 필드가
 * 없다고 믿는다.
 */
public final class CustomFieldRow {

    private final long id;
    private final String name;
    private final String typeKey;

    public CustomFieldRow(long id, String name, String typeKey) {
        this.id = id;
        this.name = name;
        this.typeKey = typeKey;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTypeKey() {
        return typeKey;
    }
}
