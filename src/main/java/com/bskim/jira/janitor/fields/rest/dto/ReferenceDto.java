package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.Reference;

import java.util.ArrayList;
import java.util.List;

/** 참조 1건. */
public class ReferenceDto {

    public String type;
    public String targetName;
    public String targetId;
    public String detail;
    public String detailKey;
    public String adminUrl;
    public boolean risky;
    public List<String> projects = new ArrayList<String>();

    public ReferenceDto() {
    }

    public ReferenceDto(Reference reference) {
        this.type = reference.getType().getCode();
        this.targetName = reference.getTargetName();
        this.targetId = reference.getTargetId();
        this.detail = reference.getDetail();
        this.detailKey = reference.getDetailI18nKey();
        this.adminUrl = reference.getAdminUrl();
        this.risky = reference.isRisky();
        this.projects = new ArrayList<String>(reference.getProjects());
    }
}
