package com.bskim.jira.janitor.fields.deep;

import com.atlassian.jira.util.json.JSONArray;
import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.store.ProblemJson;
import com.bskim.jira.janitor.fields.store.ResultCodec;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bskim.jira.janitor.fields.store.SnapshotEnvelope.nullSafe;
import static com.bskim.jira.janitor.fields.store.SnapshotEnvelope.optString;

/**
 * 심층 스캔 결과 본문 ↔ JSON. 봉투(판·버전·실패)는 {@code SnapshotEnvelope} 이 맡는다.
 *
 * <p>SCHEMA 2: 행 단위 히트를 테이블 단위 {@link DeepTableMatch} 로 바꿨고
 * skippedPrefixes 를 뺐다(코드 상수라 실어 나를 이유가 없다).
 */
public final class DeepResultCodec implements ResultCodec<DeepScanResult> {

    /** 3 — "확인 불가" 항목이 i18n 키와 인자를 담는다(docs/00 41번). */
    public static final int SCHEMA = 3;

    public static final DeepResultCodec INSTANCE = new DeepResultCodec();

    private DeepResultCodec() {
    }

    @Override
    public int schema() {
        return SCHEMA;
    }

    @Override
    public JSONObject write(DeepScanResult result) throws JSONException {
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

        json.put("problems", ProblemJson.write(result.getProblems()));
        return json;
    }

    @Override
    public DeepScanResult read(JSONObject json) throws JSONException {
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

        List<ScanProblem> problems = ProblemJson.read(json.optJSONArray("problems"));

        return new DeepScanResult(new Date(json.getLong("startedAt")),
                new Date(json.getLong("finishedAt")), json.optInt("tablesScanned", 0),
                json.optLong("rowsCounted", 0L), matches, problems);
    }
}
