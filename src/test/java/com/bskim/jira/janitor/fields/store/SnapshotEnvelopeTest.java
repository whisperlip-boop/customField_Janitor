package com.bskim.jira.janitor.fields.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;
import com.bskim.jira.janitor.fields.scan.ScanFailure;

import org.junit.Test;

/** 봉투 자체의 규칙. 결과 본문은 문자열 하나짜리 가짜 코덱으로 대신한다. */
public class SnapshotEnvelopeTest {

    /** 결과가 문자열 하나인 코덱. 판은 7. */
    private static class Words implements ResultCodec<String> {
        @Override public int schema() { return 7; }
        @Override public JSONObject write(String result) throws JSONException {
            return new JSONObject().put("w", result);
        }
        @Override public String read(JSONObject json) throws JSONException {
            return json.getString("w");
        }
    }

    private static final Words CODEC = new Words();

    @Test
    public void 결과와_실패와_저장_시각이_왕복한다() throws Exception {
        ScanFailure failure = new ScanFailure(new Date(1000), new Date(2000), "터졌다");
        long before = System.currentTimeMillis();
        SnapshotEnvelope.Snapshot<String> back =
                SnapshotEnvelope.read(CODEC, SnapshotEnvelope.write(CODEC, "hello", failure, "1.4.0"), "1.4.0");
        assertFalse(back.isRejected());
        assertEquals("hello", back.result);
        assertEquals("터졌다", back.failure.getMessage());
        assertEquals(2000L, back.failure.getFinishedAt().getTime());
        assertTrue(back.savedAt.getTime() >= before);
    }

    @Test
    public void 결과_없이_실패만도_저장된다() throws Exception {
        ScanFailure failure = new ScanFailure(new Date(1000), new Date(2000), null);
        SnapshotEnvelope.Snapshot<String> back =
                SnapshotEnvelope.read(CODEC, SnapshotEnvelope.write(CODEC, null, failure, "1.4.0"), "1.4.0");
        assertNull(back.result);
        assertNull(back.failure.getMessage());
    }

    @Test
    public void 비어_있으면_null_이다() throws Exception {
        assertNull(SnapshotEnvelope.read(CODEC, null, "1.4.0"));
        assertNull(SnapshotEnvelope.read(CODEC, "  ", "1.4.0"));
    }

    @Test
    public void 버릴_때는_이유를_남긴다() throws Exception {
        String json = SnapshotEnvelope.write(CODEC, "x", null, "1.3.2");
        assertEquals("플러그인 버전 1.3.2 ≠ 1.4.0", SnapshotEnvelope.read(CODEC, json, "1.4.0").rejected);

        ResultCodec<String> newer = new Words() {
            @Override public int schema() { return 8; }
        };
        assertEquals("결과 판 7 ≠ 8", SnapshotEnvelope.read(newer, json, "1.3.2").rejected);

        // 1.3.2 까지의 봉투에는 envelope 가 없다
        assertEquals("봉투 판 -1 ≠ 1",
                SnapshotEnvelope.read(CODEC, "{\"schema\":7,\"pluginVersion\":\"1.3.2\"}", "1.3.2").rejected);
    }

    @Test
    public void 버전이_없는_쪽과_빈_문자열은_같다() throws Exception {
        String json = SnapshotEnvelope.write(CODEC, "x", null, null);
        assertFalse(SnapshotEnvelope.read(CODEC, json, "").isRejected());
        assertFalse(SnapshotEnvelope.read(CODEC, json, null).isRejected());
    }
}
