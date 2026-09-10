package com.bskim.jira.janitor.fields.deep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 심층 스캔이 훑을 테이블 하나. {@code DatabaseMetaData} 에서 만든다. */
public final class DeepTable {

    private final String name;
    private final String idColumn;
    private final List<String> textColumns;
    private final long rowCount;

    public DeepTable(String name, String idColumn, List<String> textColumns, long rowCount) {
        this.name = name;
        this.idColumn = idColumn;
        this.textColumns = Collections.unmodifiableList(new ArrayList<String>(textColumns));
        this.rowCount = rowCount;
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

    /** 스캔 전에 센 행 수. 관리자에게 비용을 알려주는 유일한 숫자다. */
    public long getRowCount() {
        return rowCount;
    }

    /** AO 프리픽스({@code AO_60DB71}). 어느 앱인지는 알 수 없고 이것이 유일한 단서다. */
    public String getPrefix() {
        String[] parts = name.split("_");
        return parts.length >= 2 ? parts[0] + "_" + parts[1] : name;
    }
}
