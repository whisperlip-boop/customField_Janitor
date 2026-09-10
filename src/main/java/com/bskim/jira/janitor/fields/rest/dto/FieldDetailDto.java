package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.deep.DeepTableMatch;
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
    /**
     * 심층 스캔의 문자열 일치. 심층 스캔을 아직 안 돌렸으면 null(빈 목록과 구분한다 —
     * "없다"와 "안 봤다"는 다르다). 참조가 아니므로 references 에 섞지 않는다.
     * 전에는 HTML 상세 화면에만 있고 REST 에는 없었다(리뷰 지적).
     */
    public List<DeepMatchDto> deepMatches;

    public FieldDetailDto() {
    }

    public FieldDetailDto(FieldUsage field, String statusLabel, String verdict, List<DeepTableMatch> deepMatches) {
        this(field, statusLabel, verdict);
        if (deepMatches != null) {
            this.deepMatches = new ArrayList<DeepMatchDto>();
            for (DeepTableMatch match : deepMatches) {
                this.deepMatches.add(new DeepMatchDto(match));
            }
        }
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
