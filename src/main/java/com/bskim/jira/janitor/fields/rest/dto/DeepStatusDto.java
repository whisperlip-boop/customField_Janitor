package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.deep.DeepScanPolicy;
import com.bskim.jira.janitor.fields.deep.DeepScanProgress;
import com.bskim.jira.janitor.fields.deep.DeepScanResult;
import com.bskim.jira.janitor.fields.scan.ScanFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 심층 스캔의 진행률과 마지막 결과 요약. 화면이 1.5초마다 폴링한다. */
public class DeepStatusDto {

    public String state;
    public int tableIndex;
    public int tableTotal;
    public String tableName;
    public int percent;
    public String error;

    public boolean hasResult;
    public String lastScanAt;
    public int fieldCount;
    public int tablesScanned;
    public long rowsCounted;
    public List<String> skippedPrefixes = new ArrayList<String>(new TreeSet<String>(DeepScanPolicy.SKIPPED_PREFIXES));
    public int problemCount;

    /** 마지막 심층 스캔의 실패. 스냅샷과 함께 복원된다 — 화면 배너와 같은 값. */
    public String lastFailure;
    public String lastFailureAt;
    public boolean resultStale;
    /** 409 일 때 무엇이 막았는가: "scan" / "deep" / null. */
    public String blockedBy;

    public DeepStatusDto() {
    }

    public DeepStatusDto(DeepScanProgress progress, DeepScanResult result, ScanFailure lastFailure,
                         String blockedBy) {
        this.blockedBy = blockedBy;
        if (lastFailure != null) {
            this.lastFailure = lastFailure.getMessage();
            this.lastFailureAt = FieldSummaryDto.formatIso(lastFailure.getFinishedAt());
            this.resultStale = result != null && lastFailure.getFinishedAt().after(result.getFinishedAt());
        }
        this.state = progress.getState().name();
        this.tableIndex = progress.getTableIndex();
        this.tableTotal = progress.getTableTotal();
        this.tableName = progress.getTableName();
        this.percent = progress.getPercent();
        this.error = progress.getError();

        this.hasResult = result != null;
        if (result != null) {
            this.lastScanAt = FieldSummaryDto.formatIso(result.getFinishedAt());
            this.fieldCount = result.getFieldCount();
            this.tablesScanned = result.getTablesScanned();
            this.rowsCounted = result.getRowsCounted();
            this.problemCount = result.getProblems().size();
        }
    }
}
