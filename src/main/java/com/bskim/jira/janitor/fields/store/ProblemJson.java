package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.util.json.JSONArray;
import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.model.ScanProblem;

import java.util.ArrayList;
import java.util.List;

import static com.bskim.jira.janitor.fields.store.SnapshotEnvelope.nullSafe;
import static com.bskim.jira.janitor.fields.store.SnapshotEnvelope.optString;

/**
 * "확인 불가" 항목 ↔ JSON. 두 결과 코덱이 같은 모양으로 쓰도록 한 곳에 둔다 —
 * 같은 코드를 두 벌 두면 한쪽만 고쳐지는 일이 이 저장소에서 세 번 있었다(docs/00 40번).
 */
public final class ProblemJson {

    private ProblemJson() {
    }

    public static JSONArray write(List<ScanProblem> problems) throws JSONException {
        JSONArray array = new JSONArray();
        for (ScanProblem problem : problems) {
            JSONObject one = new JSONObject();
            one.put("area", nullSafe(problem.getArea()));
            one.put("target", nullSafe(problem.getTarget()));
            one.put("messageKey", nullSafe(problem.getMessageKey()));
            one.put("args", new JSONArray(problem.getMessageArgs()));
            one.put("message", nullSafe(problem.getMessage()));
            array.put(one);
        }
        return array;
    }

    /** {@code messageKey} 가 없는 옛 스냅샷은 {@code message} 만 들고 온다(그대로 그린다). */
    public static List<ScanProblem> read(JSONArray array) throws JSONException {
        List<ScanProblem> problems = new ArrayList<ScanProblem>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject one = array.getJSONObject(i);
            List<String> args = new ArrayList<String>();
            JSONArray argsJson = one.optJSONArray("args");
            for (int j = 0; argsJson != null && j < argsJson.length(); j++) {
                args.add(argsJson.getString(j));
            }
            problems.add(ScanProblem.restored(optString(one, "area"), optString(one, "target"),
                    optString(one, "messageKey"), args, optString(one, "message")));
        }
        return problems;
    }
}
