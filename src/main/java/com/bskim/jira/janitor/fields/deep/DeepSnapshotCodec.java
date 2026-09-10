package com.bskim.jira.janitor.fields.deep;

import com.atlassian.jira.util.json.JSONArray;
import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.scan.ScanFailure;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 심층 스캔 결과 ↔ JSON. 일반 스캔의 {@code SnapshotCodec} 과 같은 규칙이다 —
 * 판(스키마·플러그인 버전)이 다르면 버리고, 결과와 실패를 한 봉투에 담는다.
 *
 * <p>SCHEMA 2: 행 단위 히트를 테이블 단위 {@link DeepTableMatch} 로 바꿨고
 * skippedPrefixes 를 뺐다(코드 상수라 실어 나를 이유가 없다).
 */
public final class DeepSnapshotCodec {

    public static final int SCHEMA = 2;

    private DeepSnapshotCodec() {
    }

    /** 스냅샷 한 장. 결과와 실패를 <b>함께</b> 담는다. */
    public static final class Snapshot {

        public final DeepScanResult result;
        public final ScanFailure failure;

        public Snapshot(DeepScanResult result, ScanFailure failure) {
            this.result = result;
            this.failure = failure;
        }
    }

    public static String write(DeepScanResult result, ScanFailure failure, String pluginVersion)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("pluginVersion", pluginVersion == null ? "" : pluginVersion);
        root.put("result", result == null ? JSONObject.NULL : writeResult(result));
        root.put("failure", failure == null ? JSONObject.NULL : writeFailure(failure));
        return root.toString();
    }

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
        return new Snapshot(
                root.isNull("result") ? null : readResult(root.getJSONObject("result")),
                root.isNull("failure") ? null : readFailure(root.getJSONObject("failure")));
    }

    private static JSONObject writeFailure(ScanFailure failure) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startedAt", failure.getStartedAt().getTime());
        json.put("finishedAt", failure.getFinishedAt().getTime());
        json.put("message", nullSafe(failure.getMessage()));
        return json;
    }

    private static ScanFailure readFailure(JSONObject json) throws JSONException {
        return new ScanFailure(new Date(json.getLong("startedAt")), new Date(json.getLong("finishedAt")),
                optString(json, "message"));
    }

    private static JSONObject writeResult(DeepScanResult result) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startedAt", result.getStartedAt().getTime());
        json.put("finishedAt", result.getFinishedAt().getTime());
        json.put("tablesScanned", result.getTablesScanned());
        json.put("rowsCounted", result.getRowsCounted());

        JSONObject matches = new JSONObject();
        for (Map.Entry<Long, List<DeepTableMatch>> entry : result.getMatchesByField().entrySet()) {
            JSONArray forField = new JSONArray();
            for (DeepTableMatch match : entry.getValue()) {
                JSONObject one = new JSONObject();
                one.put("table", match.getTable());
                one.put("count", match.getMatchCount());
                one.put("rows", new JSONArray(match.getSampleRowIds()));
                forField.put(one);
            }
            matches.put(String.valueOf(entry.getKey()), forField);
        }
        json.put("matches", matches);

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

    private static DeepScanResult readResult(JSONObject json) throws JSONException {
        Map<Long, List<DeepTableMatch>> matches = new LinkedHashMap<Long, List<DeepTableMatch>>();
        JSONObject matchesJson = json.optJSONObject("matches");
        if (matchesJson != null) {
            JSONArray keys = matchesJson.names();
            for (int i = 0; keys != null && i < keys.length(); i++) {
                String key = keys.getString(i);
                JSONArray forField = matchesJson.getJSONArray(key);
                List<DeepTableMatch> list = new ArrayList<DeepTableMatch>();
                for (int j = 0; j < forField.length(); j++) {
                    JSONObject one = forField.getJSONObject(j);
                    List<String> rows = new ArrayList<String>();
                    JSONArray rowsJson = one.optJSONArray("rows");
                    for (int k = 0; rowsJson != null && k < rowsJson.length(); k++) {
                        rows.add(rowsJson.getString(k));
                    }
                    list.add(new DeepTableMatch(one.getString("table"), one.optInt("count", rows.size()), rows));
                }
                try {
                    matches.put(Long.valueOf(key), list);
                } catch (NumberFormatException e) {
                    // 필드 ID 가 아닌 키는 우리가 쓴 것이 아니다. 그 한 칸만 버린다.
                }
            }
        }

        List<ScanProblem> problems = new ArrayList<ScanProblem>();
        JSONArray problemJson = json.optJSONArray("problems");
        for (int i = 0; problemJson != null && i < problemJson.length(); i++) {
            JSONObject one = problemJson.getJSONObject(i);
            problems.add(new ScanProblem(optString(one, "area"), optString(one, "target"),
                    optString(one, "message")));
        }

        return new DeepScanResult(new Date(json.getLong("startedAt")),
                new Date(json.getLong("finishedAt")), json.optInt("tablesScanned", 0),
                json.optLong("rowsCounted", 0L), matches, problems);
    }

    /** {@code JSONObject.optString} 은 없을 때 빈 문자열을 준다. 우리는 null 이 필요하다. */
    private static String optString(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }

    private static Object nullSafe(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
