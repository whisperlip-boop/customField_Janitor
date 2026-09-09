package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;

import java.util.ArrayList;
import java.util.List;

/** 상세 화면(기획서 6.2)용. 요약 + 참조 전체 + 컨텍스트. */
public class FieldDetailDto {

    public FieldSummaryDto summary;
    public List<ReferenceDto> references = new ArrayList<ReferenceDto>();
    public List<String> contextProjects = new ArrayList<String>();
    public List<String> contextIssueTypes = new ArrayList<String>();
    public boolean globalContext;
    public String verdictKey;
    public String verdict;

    public FieldDetailDto() {
    }

    public FieldDetailDto(FieldUsage field, String statusLabel, String verdict) {
        this.summary = new FieldSummaryDto(field, statusLabel);
        for (Reference reference : field.getReferences()) {
            this.references.add(new ReferenceDto(reference));
        }
        this.contextProjects = new ArrayList<String>(field.getContextProjects());
        this.contextIssueTypes = new ArrayList<String>(field.getContextIssueTypes());
        this.globalContext = field.isGlobalContext();
        this.verdictKey = field.getVerdictI18nKey();
        this.verdict = verdict;
    }
}
