package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.ReferenceType;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 목록 화면의 한 행. 커스텀 필드 관리 화면 주입(기획서 6.3)도 이걸 쓴다.
 *
 * <p>{@code statusLabel}을 서버에서 번역해 넣는 이유: 주입 JS가 i18n 번들을 따로
 * 들고 있지 않아도 되게 하려는 것이다. 기계가 읽는 값은 {@code status}(코드)다.
 */
public class FieldSummaryDto {

    public long id;
    public String fieldId;
    public String name;
    public String type;
    public String typeKey;
    public boolean typeAvailable;
    public boolean locked;

    public String status;
    public String statusLabel;
    public String statusLozenge;

    public long issuesWithValue;
    public long valueRows;
    public boolean valueCountUnavailable;

    public String lastValueChange;
    public boolean lastValueChangeAmbiguous;
    public boolean duplicateName;

    public int managed;
    public int screens;
    public int fieldConfigs;
    public int contexts;
    public int workflows;
    public int filters;
    public int permissionSchemes;
    public int notificationSchemes;
    public int issueSecuritySchemes;
    public int gadgets;
    public int totalReferences;
    public int riskyReferences;

    public String detailUrl;
    public String configureUrl;
    public String deleteUrl;

    public FieldSummaryDto() {
    }

    public FieldSummaryDto(FieldUsage field, String statusLabel) {
        this.id = field.getNumericId();
        this.fieldId = field.getFieldId();
        this.name = field.getName();
        this.type = field.getTypeName();
        this.typeKey = field.getTypeKey();
        this.typeAvailable = field.isTypeAvailable();
        this.locked = field.isLocked();

        this.status = field.getStatus().getCode();
        this.statusLabel = statusLabel;
        this.statusLozenge = field.getStatus().getLozengeType();

        this.issuesWithValue = field.getIssuesWithValue();
        this.valueRows = field.getValueRows();
        this.valueCountUnavailable = field.isValueCountUnavailable();

        this.lastValueChange = formatIso(field.getLastValueChange());
        this.lastValueChangeAmbiguous = field.isLastValueChangeAmbiguous();
        this.duplicateName = field.isDuplicateName();

        this.managed = field.getReferenceCount(ReferenceType.MANAGED);
        this.screens = field.getReferenceCount(ReferenceType.SCREEN);
        this.fieldConfigs = field.getReferenceCount(ReferenceType.FIELD_CONFIG);
        this.contexts = field.getReferenceCount(ReferenceType.CONTEXT);
        this.workflows = field.getReferenceCount(ReferenceType.WORKFLOW);
        this.filters = field.getReferenceCount(ReferenceType.FILTER);
        this.permissionSchemes = field.getReferenceCount(ReferenceType.PERMISSION_SCHEME);
        this.notificationSchemes = field.getReferenceCount(ReferenceType.NOTIFICATION_SCHEME);
        this.issueSecuritySchemes = field.getReferenceCount(ReferenceType.ISSUE_SECURITY_SCHEME);
        this.gadgets = field.getReferenceCount(ReferenceType.GADGET);
        this.totalReferences = field.getTotalReferenceCount();
        this.riskyReferences = field.getRiskyReferenceCount();

        this.detailUrl = "/secure/admin/CustomFieldUsage.jspa?fieldId=" + field.getFieldId();
        this.configureUrl = field.getConfigureUrl();
        this.deleteUrl = field.getDeleteUrl();
    }

    static String formatIso(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }
}
