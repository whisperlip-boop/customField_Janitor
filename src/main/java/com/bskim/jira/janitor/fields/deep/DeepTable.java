package com.bskim.jira.janitor.fields.deep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 심층 스캔이 훑을 테이블 하나. {@code DatabaseMetaData} 에서 만든다.
 * 행 수는 여기 두지 않는다 — 테이블 루프 안에서 세어 진행률과 함께 낸다(v1.3.2).
 */
public final class DeepTable {

    private final String name;
    private final String idColumn;
    private final List<String> textColumns;

    public DeepTable(String name, String idColumn, List<String> textColumns) {
        this.name = name;
        this.idColumn = idColumn;
        this.textColumns = Collections.unmodifiableList(new ArrayList<String>(textColumns));
    }

    public String getName() {
        return name;
    }

    /** 행을 지목할 컬럼. 기본키가 없으면 null 이고, 그때는 행 ID 없이 건수만 낸다. */
    public String getIdColumn() {
        return idColumn;
    }

    public List<String> getTextColumns() {
        return textColumns;
    }
}
