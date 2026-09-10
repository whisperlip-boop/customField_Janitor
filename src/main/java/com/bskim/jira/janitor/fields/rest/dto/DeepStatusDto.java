package com.bskim.jira.janitor.fields.rest.dto;

import com.bskim.jira.janitor.fields.deep.DeepScanProgress;
import com.bskim.jira.janitor.fields.deep.DeepScanResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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
    public List<String> skippedPrefixes = new ArrayList<String>();
    public int problemCount;

    public DeepStatusDto() {
    }

    public DeepStatusDto(DeepScanProgress progress, DeepScanResult result) {
        this.state = progress.getState().name();
        this.tableIndex = progress.getTableIndex();
        this.tableTotal = progress.getTableTotal();
        this.tableName = progress.getTableName();
        this.percent = progress.getPercent();
        this.error = progress.getError();

        this.hasResult = result != null;
        if (result != null) {
            this.lastScanAt = utc(result.getFinishedAt());
            this.fieldCount = result.getFieldCount();
            this.tablesScanned = result.getTablesScanned();
            this.rowsCounted = result.getRowsCounted();
            this.skippedPrefixes = new ArrayList<String>(result.getSkippedPrefixes());
            this.problemCount = result.getProblems().size();
        }
    }

    private static String utc(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }
}
