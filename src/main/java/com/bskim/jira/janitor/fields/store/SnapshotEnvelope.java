package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.scan.ScanFailure;

import java.util.Date;

/**
 * 스냅샷 봉투. 일반 스캔과 심층 스캔이 <b>같은</b> 겉모양으로 저장된다:
 * {@code {envelope, schema, pluginVersion, savedAt, result, failure}}. 결과 본문만
 * {@link ResultCodec} 이 다르다.
 *
 * <p>전에는 코덱 둘이 각자 봉투를 썼다. 실패 직렬화·판 검사·null 처리가 두 번 있었고,
 * 한쪽에만 고친 것이 리뷰에서 결함으로 나왔다(v1.3.2 의 #2). 규칙이 한 곳에 있어야 한다.
 *
 * <p><b>판·플러그인 버전이 다르면 버린다.</b> 관대하게 읽으면 안 된다: v1.0.2 가 저장한
 * 스냅샷에는 컬럼 참조가 없어서 컬럼으로만 쓰이던 필드가 [미사용]으로 나온다. 정보가
 * 없는 것이 삭제 신호로 바뀌는 것이 이 도구 최악의 실패다(docs/00 15·33번). 버리면
 * "아직 스캔한 적 없음"이 되고 관리자는 스캔을 다시 누른다 — 잃는 것은 시간뿐이다.
 * 리뷰는 "모양 키"(열거형 이름 목록)로 리팩터링 릴리스에서 결과를 살리자고 했지만
 * 받지 않았다 — 열거형이 안 바뀐 수집기 버그 수정 뒤에 옛 표가 살아남는 구멍이
 * 생기고, 그건 정확히 33번이 막는 실패다(docs/00 40번).
 */
public final class SnapshotEnvelope {

    /** 봉투 자체의 판. 봉투 형식을 바꾸면 올린다. */
    public static final int ENVELOPE_SCHEMA = 1;

    private SnapshotEnvelope() {
    }

    /**
     * 읽은 스냅샷. 버려졌으면 {@link #rejected} 에 이유가 있고 나머지는 비어 있다 —
     * 이유를 로그에 남겨야 한다. 이것 없이 업그레이드하면 관리자에게는 "결과가 그냥
     * 없어졌다"로 보인다.
     */
    public static final class Snapshot<R> {

        public final R result;
        public final ScanFailure failure;
        public final Date savedAt;
        public final String rejected;

        Snapshot(R result, ScanFailure failure, Date savedAt) {
            this.result = result;
            this.failure = failure;
            this.savedAt = savedAt;
            this.rejected = null;
        }

        Snapshot(String rejected) {
            this.result = null;
            this.failure = null;
            this.savedAt = null;
            this.rejected = rejected;
        }

        public boolean isRejected() {
            return rejected != null;
        }
    }

    public static <R> String write(ResultCodec<R> codec, R result, ScanFailure failure, String pluginVersion)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("envelope", ENVELOPE_SCHEMA);
        root.put("schema", codec.schema());
        root.put("pluginVersion", pluginVersion == null ? "" : pluginVersion);
        root.put("savedAt", System.currentTimeMillis());
        root.put("result", result == null ? JSONObject.NULL : codec.write(result));
        root.put("failure", failure == null ? JSONObject.NULL : writeFailure(failure));
        return root.toString();
    }

    /**
     * @return 비어 있으면 null. 판이나 버전이 다르면 {@link Snapshot#isRejected()} 인 값.
     * @throws JSONException JSON 이 아니거나 필드가 깨졌을 때 — 부르는 쪽이 "해석 실패"로 남긴다.
     */
    public static <R> Snapshot<R> read(ResultCodec<R> codec, String json, String pluginVersion)
            throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        JSONObject root = new JSONObject(json);
        int envelope = root.optInt("envelope", -1);
        if (envelope != ENVELOPE_SCHEMA) {
            return new Snapshot<R>("봉투 판 " + envelope + " ≠ " + ENVELOPE_SCHEMA);
        }
        int schema = root.optInt("schema", -1);
        if (schema != codec.schema()) {
            return new Snapshot<R>("결과 판 " + schema + " ≠ " + codec.schema());
        }
        String expected = pluginVersion == null ? "" : pluginVersion;
        String stored = root.optString("pluginVersion", "");
        if (!stored.equals(expected)) {
            return new Snapshot<R>("플러그인 버전 " + stored + " ≠ " + expected);
        }
        return new Snapshot<R>(
                root.isNull("result") ? null : codec.read(root.getJSONObject("result")),
                root.isNull("failure") ? null : readFailure(root.getJSONObject("failure")),
                new Date(root.optLong("savedAt", 0L)));
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

    /** {@code JSONObject.optString} 은 없을 때 빈 문자열을 준다. 우리는 null 이 필요하다. */
    public static String optString(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }

    public static Object nullSafe(String value) {
        return value == null ? JSONObject.NULL : value;
    }
}
