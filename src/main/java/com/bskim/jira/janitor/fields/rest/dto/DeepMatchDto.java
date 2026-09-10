package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.deep.DeepTableMatch;

import java.util.ArrayList;
import java.util.List;

/** 심층 스캔의 문자열 일치 하나(테이블 단위). 참조가 아니다 — 상태 라벨에 안 들어간다. */
public class DeepMatchDto {

    public String table;
    public int matchCount;
    public List<String> sampleRowIds = new ArrayList<String>();

    public DeepMatchDto() {
    }

    public DeepMatchDto(DeepTableMatch match) {
        this.table = match.getTable();
        this.matchCount = match.getMatchCount();
        this.sampleRowIds = new ArrayList<String>(match.getSampleRowIds());
    }
}
