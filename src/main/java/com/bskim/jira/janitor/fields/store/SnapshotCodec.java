package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.util.json.JSONArray;
import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.model.ScanResult;
import com.bskim.jira.janitor.fields.scan.ScanFailure;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 스캔 결과 ↔ JSON. 재기동·재배포 후에도 표가 남아 있게 하는 유일한 통로다.
 *
 * <p>Jira 가 들고 있는 {@code com.atlassian.jira.util.json} 을 쓴다. Jackson 을
 * 직접 import 하면 8.13 에서 되고 8.17.1 에서 깨지는 종류의 의존이 하나 늘어난다.
 *
 * <p><b>스키마·플러그인 버전이 다르면 스냅샷을 버린다.</b> 관대하게 읽으면 안 된다:
 * v1.0.2 가 저장한 스냅샷에는 컬럼 참조가 없어서, 컬럼으로만 쓰이던 필드가
 * [미사용]으로 나온다. 정보가 없는 것이 삭제 신호로 바뀌는 것이 이 도구에서 가장
 * 나쁜 실패다(docs/00 15번). 버리면 "아직 스캔한 적 없음"이 되고 관리자는 스캔을
 * 다시 누른다 — 잃는 것은 시간뿐이다.
 */
public final class SnapshotCodec {

    /** 저장 형식 자체의 판(형식을 바꾸면 올린다). */
    public static final int SCHEMA = 1;

    private SnapshotCodec() {
    }

    /** 스냅샷 한 장. 결과와 실패를 함께 담는다. */
    public static final class Snapshot {

        public final ScanResult result;
        public final ScanFailure failure;
        public final Date savedAt;

        public Snapshot(ScanResult result, ScanFailure failure, Date savedAt) {
            this.result = result;
            this.failure = failure;
            this.savedAt = savedAt;
        }
    }

    public static String write(ScanResult result, ScanFailure failure, String pluginVersion)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("pluginVersion", pluginVersion == null ? "" : pluginVersion);
        root.put("savedAt", System.currentTimeMillis());
        root.put("result", result == null ? JSONObject.NULL : writeResult(result));
        root.put("failure", failure == null ? JSONObject.NULL : writeFailure(failure));
        return root.toString();
    }

    /**
     * @return 읽을 수 없거나 판이 다르면 null. 부르는 쪽은 "스냅샷 없음"으로 다룬다.
     */
    public static Snapshot read(String json, String pluginVersion) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema", -1) != SCHEMA) {
            return null;
        }
        if (!root.optString("pluginVersion", "").equals(pluginVersion == null ? "" : pluginVersion)) {
            return null;
        }
        ScanResult result = root.isNull("result") ? null : readResult(root.getJSONObject("result"));
        ScanFailure failure = root.isNull("failure") ? null : readFailure(root.getJSONObject("failure"));
        return new Snapshot(result, failure, new Date(root.optLong("savedAt", 0L)));
    }

    private static JSONObject writeFailure(ScanFailure failure) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startedAt", failure.getStartedAt().getTime());
        json.put("finishedAt", failure.getFinishedAt().getTime());
        json.put("message", failure.getMessage() == null ? JSONObject.NULL : failure.getMessage());
        return json;
    }

    private static ScanFailure readFailure(JSONObject json) throws JSONException {
        return new ScanFailure(new Date(json.getLong("startedAt")),
                new Date(json.getLong("finishedAt")),
                json.isNull("message") ? null : json.getString("message"));
    }

    private static JSONObject writeResult(ScanResult result) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startedAt", result.getStartedAt().getTime());
        json.put("finishedAt", result.getFinishedAt().getTime());
        json.put("fieldListDegraded", result.isFieldListDegraded());

        JSONArray fields = new JSONArray();
        for (FieldUsage field : result.getFields()) {
            fields.put(writeField(field));
        }
        json.put("fields", fields);

        JSONArray problems = new JSONArray();
        for (ScanProblem problem : result.getProblems()) {
            JSONObject one = new JSONObject();
            one.put("area", nullSafe(problem.getArea()));
            one.put("target", nullSafe(problem.getTarget()));
            one.put("message", nullSafe(problem.getMessage()));
            problems.put(one);
        }
        json.put("problems", problems);
        return json;
    }

    private static ScanResult readResult(JSONObject json) throws JSONException {
        List<FieldUsage> fields = new ArrayList<FieldUsage>();
        JSONArray fieldArray = json.getJSONArray("fields");
        for (int i = 0; i < fieldArray.length(); i++) {
            fields.add(readField(fieldArray.getJSONObject(i)));
        }

        List<ScanProblem> problems = new ArrayList<ScanProblem>();
        JSONArray problemArray = json.optJSONArray("problems");
        if (problemArray != null) {
            for (int i = 0; i < problemArray.length(); i++) {
                JSONObject one = problemArray.getJSONObject(i);
                problems.add(new ScanProblem(optString(one, "area"), optString(one, "target"),
                        optString(one, "message")));
            }
        }

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

    /** {@code JSONObject.optString} 은 없을 때 빈 문자열을 준다. 우리는 null 이 필요하다. */
    private static String optString(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }

    private static Object nullSafe(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
