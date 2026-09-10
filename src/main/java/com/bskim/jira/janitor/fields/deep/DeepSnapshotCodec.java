package com.bskim.jira.janitor.fields.deep;

import com.atlassian.jira.util.json.JSONArray;
import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.model.ScanProblem;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 심층 스캔 결과 ↔ JSON. 일반 스캔의 {@code SnapshotCodec} 과 같은 규칙이다 —
 * 판(스키마·플러그인 버전)이 다르면 버린다.
 *
 * <p>여기서 판을 검사하는 이유는 일반 스캔과 조금 다르다. 심층 스캔의 결과는 라벨을
 * 만들지 않으므로 "거꾸로 나온다"는 위험은 없다. 대신 <b>건너뛴 테이블 목록과 행 수</b>
 * 같은 안내가 옛 코드 기준이면 화면 설명과 실제가 어긋난다.
 */
public final class DeepSnapshotCodec {

    public static final int SCHEMA = 1;

    private DeepSnapshotCodec() {
    }

    public static String write(DeepScanResult result, String pluginVersion) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("pluginVersion", pluginVersion == null ? "" : pluginVersion);
        root.put("result", result == null ? JSONObject.NULL : writeResult(result));
        return root.toString();
    }

    public static DeepScanResult read(String json, String pluginVersion) throws JSONException {
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
        return root.isNull("result") ? null : readResult(root.getJSONObject("result"));
    }

    private static JSONObject writeResult(DeepScanResult result) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startedAt", result.getStartedAt().getTime());
        json.put("finishedAt", result.getFinishedAt().getTime());
        json.put("tablesScanned", result.getTablesScanned());
        json.put("rowsCounted", result.getRowsCounted());
        json.put("skippedPrefixes", new JSONArray(result.getSkippedPrefixes()));

        JSONObject hits = new JSONObject();
        for (Map.Entry<Long, List<DeepHit>> entry : result.getHitsByField().entrySet()) {
            JSONArray forField = new JSONArray();
            for (DeepHit hit : entry.getValue()) {
                JSONObject one = new JSONObject();
                one.put("table", hit.getTable());
                one.put("rowId", hit.getRowId() == null ? JSONObject.NULL : hit.getRowId());
                forField.put(one);
            }
            hits.put(String.valueOf(entry.getKey()), forField);
        }
        json.put("hits", hits);

        JSONArray problems = new JSONArray();
        for (ScanProblem problem : result.getProblems()) {
            JSONObject one = new JSONObject();
            one.put("area", problem.getArea());
            one.put("target", problem.getTarget());
            one.put("message", problem.getMessage());
            problems.put(one);
        }
        json.put("problems", problems);
        return json;
    }

    private static DeepScanResult readResult(JSONObject json) throws JSONException {
        Map<Long, List<DeepHit>> hits = new LinkedHashMap<Long, List<DeepHit>>();
        JSONObject hitsJson = json.optJSONObject("hits");
        if (hitsJson != null) {
            JSONArray keys = hitsJson.names();
            for (int i = 0; keys != null && i < keys.length(); i++) {
                String key = keys.getString(i);
                JSONArray forField = hitsJson.getJSONArray(key);
                List<DeepHit> list = new ArrayList<DeepHit>();
                for (int j = 0; j < forField.length(); j++) {
                    JSONObject one = forField.getJSONObject(j);
                    list.add(new DeepHit(one.getString("table"),
                            one.isNull("rowId") ? null : one.getString("rowId")));
                }
                try {
                    hits.put(Long.valueOf(key), list);
                } catch (NumberFormatException e) {
                    // 필드 ID 가 아닌 키는 우리가 쓴 것이 아니다. 그 한 칸만 버린다.
                }
            }
        }

        List<ScanProblem> problems = new ArrayList<ScanProblem>();
        JSONArray problemJson = json.optJSONArray("problems");
        for (int i = 0; problemJson != null && i < problemJson.length(); i++) {
            JSONObject one = problemJson.getJSONObject(i);
            problems.add(new ScanProblem(one.optString("area", null), one.optString("target", null),
                    one.optString("message", null)));
        }

        List<String> skipped = new ArrayList<String>();
        JSONArray skippedJson = json.optJSONArray("skippedPrefixes");
        for (int i = 0; skippedJson != null && i < skippedJson.length(); i++) {
            skipped.add(skippedJson.getString(i));
        }

        return new DeepScanResult(new Date(json.getLong("startedAt")),
                new Date(json.getLong("finishedAt")), json.optInt("tablesScanned", 0),
                skipped, json.optLong("rowsCounted", 0L), hits, problems);
    }
}
