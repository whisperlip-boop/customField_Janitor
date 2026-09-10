package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.util.json.JSONArray;
import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.model.ScanResult;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.bskim.jira.janitor.fields.store.SnapshotEnvelope.nullSafe;
import static com.bskim.jira.janitor.fields.store.SnapshotEnvelope.optString;

/**
 * 일반 스캔 결과 본문 ↔ JSON. 봉투는 {@link SnapshotEnvelope} 이 맡는다.
 *
 * <p>Jira 가 들고 있는 {@code com.atlassian.jira.util.json} 을 쓴다. Jackson 을 직접
 * import 하면 8.13 에서 되고 8.17.1 에서 깨지는 종류의 의존이 하나 늘어난다.
 */
public final class ScanResultCodec implements ResultCodec<ScanResult> {

    /**
     * 결과 본문의 판. 수집·판정의 뜻이 바뀌면 올린다(형식이 같아도).
     * 2 — "확인 불가" 항목이 완성된 문장 대신 i18n 키와 인자를 담는다(docs/00 41번).
     */
    public static final int SCHEMA = 2;

    public static final ScanResultCodec INSTANCE = new ScanResultCodec();

    private ScanResultCodec() {
    }

    @Override
    public int schema() {
        return SCHEMA;
    }

    @Override
    public JSONObject write(ScanResult result) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startedAt", result.getStartedAt().getTime());
        json.put("finishedAt", result.getFinishedAt().getTime());
        json.put("fieldListDegraded", result.isFieldListDegraded());

        JSONArray fields = new JSONArray();
        for (FieldUsage field : result.getFields()) {
            fields.put(writeField(field));
        }
        json.put("fields", fields);

        json.put("problems", ProblemJson.write(result.getProblems()));
        return json;
    }

    @Override
    public ScanResult read(JSONObject json) throws JSONException {
        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        JSONArray fieldArray = json.getJSONArray("fields");
        for (int i = 0; i < fieldArray.length(); i++) {
            fields.add(readField(fieldArray.getJSONObject(i)));
        }

        List<ScanProblem> problems = ProblemJson.read(json.optJSONArray("problems"));

        return new ScanResult(new Date(json.getLong("startedAt")), new Date(json.getLong("finishedAt")),
                fields, problems, json.optBoolean("fieldListDegraded", false));
    }

    private static JSONObject writeField(FieldUsage field) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", field.getNumericId());
        json.put("name", nullSafe(field.getName()));
        json.put("typeKey", nullSafe(field.getTypeKey()));
        json.put("typeName", nullSafe(field.getTypeName()));
        json.put("issuesWithValue", field.getIssuesWithValue());
        json.put("valueRows", field.getValueRows());
        json.put("lastValueChange", field.getLastValueChange() == null
                ? JSONObject.NULL : field.getLastValueChange().getTime());
        json.put("lastValueChangeAmbiguous", field.isLastValueChangeAmbiguous());
        json.put("duplicateName", field.isDuplicateName());
        json.put("typeAvailable", field.isTypeAvailable());
        json.put("globalContext", field.isGlobalContext());
        json.put("contextProjects", new JSONArray(field.getContextProjects()));
        json.put("contextIssueTypes", new JSONArray(field.getContextIssueTypes()));

        JSONArray refs = new JSONArray();
        for (Reference reference : field.getReferences()) {
            refs.put(writeReference(reference));
        }
        json.put("references", refs);
        return json;
    }

    private static FieldUsage readField(JSONObject json) throws JSONException {
        FieldUsage field = new FieldUsage(json.getLong("id"), optString(json, "name"),
                optString(json, "typeKey"), optString(json, "typeName"));
        field.setIssuesWithValue(json.optLong("issuesWithValue", 0L));
        field.setValueRows(json.optLong("valueRows", 0L));
        if (!json.isNull("lastValueChange")) {
            field.setLastValueChange(new Date(json.getLong("lastValueChange")));
        }
        field.setLastValueChangeAmbiguous(json.optBoolean("lastValueChangeAmbiguous", false));
        field.setDuplicateName(json.optBoolean("duplicateName", false));
        field.setTypeAvailable(json.optBoolean("typeAvailable", true));
        field.setGlobalContext(json.optBoolean("globalContext", false));

        for (String project : strings(json.optJSONArray("contextProjects"))) {
            field.addContextProject(project);
        }
        for (String issueType : strings(json.optJSONArray("contextIssueTypes"))) {
            field.addContextIssueType(issueType);
        }

        JSONArray refs = json.optJSONArray("references");
        if (refs != null) {
            for (int i = 0; i < refs.length(); i++) {
                Reference reference = readReference(refs.getJSONObject(i));
                if (reference != null) {
                    field.addReference(reference);
                }
            }
        }
        return field;
    }

    private static JSONObject writeReference(Reference reference) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", reference.getType().name());
        json.put("targetName", nullSafe(reference.getTargetName()));
        json.put("targetId", nullSafe(reference.getTargetId()));
        json.put("detail", nullSafe(reference.getDetail()));
        json.put("detailI18nKey", nullSafe(reference.getDetailI18nKey()));
        json.put("adminUrl", nullSafe(reference.getAdminUrl()));
        json.put("risky", reference.isRisky());
        json.put("projects", new JSONArray(reference.getProjects()));
        return json;
    }

    /**
     * @return 모르는 참조 종류면 null. 스냅샷을 쓴 쪽과 읽는 쪽의 플러그인 버전이
     *         같아야만 여기까지 오므로 실제로는 나올 수 없지만, 나온다면 그 한 건을
     *         버리는 편이 낫다 — 열거형 변환 예외로 스냅샷 전체를 잃지 않는다.
     */
    private static Reference readReference(JSONObject json) throws JSONException {
        ReferenceType type;
        try {
            type = ReferenceType.valueOf(json.getString("type"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new Reference(type,
                optString(json, "targetName"),
                optString(json, "targetId"),
                optString(json, "detail"),
                optString(json, "detailI18nKey"),
                optString(json, "adminUrl"),
                strings(json.optJSONArray("projects")),
                json.optBoolean("risky", type.isRisky()));
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> out = new ArrayList<String>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                out.add(array.getString(i));
            }
        }
        return out;
    }
}
