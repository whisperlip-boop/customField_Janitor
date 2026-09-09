package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.model.ScanResult;

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

    public ScanStatusDto() {
    }

    public ScanStatusDto(ScanProgress progress, ScanResult lastResult, String stageLabel) {
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
