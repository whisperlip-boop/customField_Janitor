package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.util.json.JSONException;
import com.atlassian.jira.util.json.JSONObject;

/**
 * 스캔 결과 한 종류 ↔ JSON. 봉투({@link SnapshotEnvelope})가 판·버전·실패를 맡고,
 * 코덱은 <b>결과 본문</b>만 안다. 일반 스캔과 심층 스캔이 각각 하나씩 구현한다.
 *
 * @param <R> 결과 타입
 */
public interface ResultCodec<R> {

    /**
     * 결과 본문 형식의 판. <b>수집·판정의 뜻이 바뀌면 올린다</b> — 형식이 같아도.
     * 봉투가 이 값이 다르면 스냅샷을 버린다.
     */
    int schema();

    JSONObject write(R result) throws JSONException;

    R read(JSONObject json) throws JSONException;
}
