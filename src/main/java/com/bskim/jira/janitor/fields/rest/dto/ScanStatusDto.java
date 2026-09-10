package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.model.ScanResult;
import com.bskim.jira.janitor.fields.scan.ScanFailure;

/**
 * 진행률 폴링 응답. 화면은 이걸로 진행 바와 "마지막 스캔" 표시를 그린다.
 *
 * <p>{@code lastScanAt}을 항상 함께 주는 이유: Data Center에서 메모리 캐시는
 * 노드별로 갈린다. 관리자가 지금 보는 숫자가 언제 것인지 알아야 한다(기획서 9장).
 */
public class ScanStatusDto {

    public String state;
    public String stage;
    public String stageLabel;
    public int stageIndex;
    public int stageTotal;
    public int percent;
    public String startedAt;
    public String error;

    public String lastScanAt;
    public long lastScanDurationMs;
    public int fieldCount;
    public int problemCount;
    public boolean hasResult;

    /**
     * 마지막 스캔의 실패. 진행률(error)은 다음 요청에서 사라지고 재기동하면 더더욱
     * 사라지지만, 이것은 스냅샷과 함께 복원된다. 화면의 빨간 배너가 그리는 것과 같은
     * 값이다 — API 만 "깨끗한 성공"으로 답하던 불일치(리뷰 지적)를 막는다.
     */
    public String lastFailure;
    public String lastFailureAt;
    /** 표가 실패보다 오래된 것인가. 참이면 result 를 최신으로 읽지 말라는 뜻이다. */
    public boolean resultStale;
    /** 409 일 때 무엇이 막았는가: "scan" / "deep" / null. 화면이 이유를 낸다. */
    public String blockedBy;

    public ScanStatusDto() {
    }

    public ScanStatusDto(ScanProgress progress, ScanResult lastResult, String stageLabel) {
        this(progress, lastResult, null, null, stageLabel);
    }

    public ScanStatusDto(ScanProgress progress, ScanResult lastResult, ScanFailure lastFailure,
                         String blockedBy, String stageLabel) {
        this.blockedBy = blockedBy;
        if (lastFailure != null) {
            this.lastFailure = lastFailure.getMessage();
            this.lastFailureAt = FieldSummaryDto.formatIso(lastFailure.getFinishedAt());
            this.resultStale = lastResult != null
                    && lastFailure.getFinishedAt().after(lastResult.getFinishedAt());
        }
        this.state = progress.getState().name();
        this.stage = progress.getStage() == null ? null : progress.getStage().name();
        this.stageLabel = stageLabel;
        this.stageIndex = progress.getStageIndex();
        this.stageTotal = progress.getStageTotal();
        this.percent = progress.getPercent();
        this.startedAt = FieldSummaryDto.formatIso(progress.getStartedAt());
        this.error = progress.getError();

        this.hasResult = lastResult != null;
        if (lastResult != null) {
            this.lastScanAt = FieldSummaryDto.formatIso(lastResult.getFinishedAt());
            this.lastScanDurationMs = lastResult.getDurationMs();
            this.fieldCount = lastResult.getFields().size();
            this.problemCount = lastResult.getProblems().size();
        }
    }
}
