package com.bskim.jira.janitor.fields.dao;

/**
 * {@code managedconfigurationitem} 테이블 한 행. "이 필드는 앱이 관리한다"는 사실이다.
 *
 * <p>Jira는 앱이 만든 필드 중 손대면 안 되는 것을 이 테이블에 등록하고, 관리 화면에
 * {@code LOCKED} / {@code MANAGED} 배지를 붙이고 잠긴 필드의 삭제 메뉴를 숨긴다.
 *
 * <p>API({@code ManagedConfigurationItemService.getManagedCustomField})가 아니라 이 테이블을
 * 직접 읽는 이유: API는 {@code CustomField} 객체를 요구하는데, 타입 제공 앱이 비활성이면
 * 그 객체가 없다. 그런데 잠긴 필드 대부분이 정확히 그 상태다(실측: Jira Agile 비활성
 * 인스턴스에서 Epic Link / Sprint / Rank가 전부 여기에 LOCKED로 남아 있었다).
 * 테이블은 앱 상태와 무관하게 유지되므로 이쪽이 엄격하게 더 많이 잡는다.
 */
public final class ManagedFieldRow {

    private final long fieldId;
    private final String accessLevel;
    private final String source;
    private final String descriptionKey;

    public ManagedFieldRow(long fieldId, String accessLevel, String source, String descriptionKey) {
        this.fieldId = fieldId;
        this.accessLevel = accessLevel;
        this.source = source;
        this.descriptionKey = descriptionKey;
    }

    public long getFieldId() {
        return fieldId;
    }

    /** {@code LOCKED} / {@code SYS_ADMIN} / {@code ADMIN}. */
    public String getAccessLevel() {
        return accessLevel;
    }

    /** {@code <플러그인 키>:<모듈 키>} 형태. 어느 앱이 잠갔는지 알려준다. */
    public String getSource() {
        return source;
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    /**
     * 관리자가 손댈 수 없는 수준인가. {@code LOCKED}면 Jira 자신도 삭제 메뉴를 숨긴다.
     * {@code ADMIN} / {@code SYS_ADMIN}은 "앱이 관리하지만 관리자는 바꿀 수 있다"는 뜻이라
     * 삭제를 막지 않는다.
     */
    public boolean isLocked() {
        return "LOCKED".equalsIgnoreCase(accessLevel);
    }

    /** 잠근 앱의 플러그인 키. 모듈 키를 떼어낸다. */
    public String getPluginKey() {
        if (source == null) {
            return null;
        }
        int colon = source.lastIndexOf(':');
        return colon < 0 ? source : source.substring(0, colon);
    }
}
