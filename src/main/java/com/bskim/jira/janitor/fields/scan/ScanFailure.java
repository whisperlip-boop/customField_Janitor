package com.bskim.jira.janitor.fields.scan;

import java.util.Date;

/**
 * 실패한 스캔 한 건의 기록. 진행률과 달리 <b>다음 화면 로드에도 남는다.</b>
 *
 * <p>왜 따로 보관하는가: 진행률({@link com.bskim.jira.janitor.fields.model.ScanProgress})
 * 은 메모리의 현재 상태이고, 화면을 새로 그리면 사라진다. 그런데 스캔이 실패했을 때
 * 화면에 남는 것은 <b>이전 스캔의 결과와 그 시각</b>이다. 실패 흔적이 없으면 관리자는
 * 방금 스캔한 결과를 보고 있다고 믿는데 실제로는 몇 주 전 스냅샷이다.
 *
 * <p>{@link #getFinishedAt()} 을 결과의 완료 시각과 비교하면 "지금 보고 있는 표가
 * 최신인가"를 화면이 스스로 판단할 수 있다.
 */
public final class ScanFailure {

    private final Date startedAt;
    private final Date finishedAt;
    private final String message;

    public ScanFailure(Date startedAt, Date finishedAt, String message) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
    }

    public Date getStartedAt() {
        return startedAt;
    }

    /** 실패한 시각. 결과의 완료 시각보다 나중이면 화면의 표는 낡은 것이다. */
    public Date getFinishedAt() {
        return finishedAt;
    }

    public String getMessage() {
        return message;
    }
}
