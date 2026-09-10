package com.bskim.jira.janitor.fields.rest;

import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.ScanProblem;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.model.ScanResult;
import com.bskim.jira.janitor.fields.rest.dto.FieldDetailDto;
import com.bskim.jira.janitor.fields.rest.dto.FieldSummaryDto;
import com.bskim.jira.janitor.fields.rest.dto.FieldsResponseDto;
import com.bskim.jira.janitor.fields.rest.dto.ProblemDto;
import com.bskim.jira.janitor.fields.rest.dto.ScanStatusDto;
import com.bskim.jira.janitor.fields.scan.ScanService;
import com.bskim.jira.janitor.fields.web.AdminGuard;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * {@code /rest/janitor/1.0/} — 목록 화면, 상세 화면, 커스텀 필드 관리 화면 주입,
 * CSV 내보내기가 모두 여기를 쓴다.
 *
 * <p>모든 메서드가 첫 줄에서 관리자 권한을 다시 검사한다(기획서 7.2 / 함정 1).
 * web-item condition만 믿으면 URL 직접 호출로 뚫린다.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class FieldsResource {

    private final ScanService scanService = ScanService.getInstance();

    /** 캐시된 전체 결과. 아직 스캔한 적이 없으면 필드 목록이 빈 채로 스캔 상태만 온다. */
    @GET
    @Path("/fields")
    public Response fields() {
        if (!AdminGuard.isAdmin()) {
            return forbidden();
        }
        ScanResult result = scanService.getLastResult();

        FieldsResponseDto response = new FieldsResponseDto();
        response.scan = status();
        response.limitation = JanitorI18n.text("janitor.fields.limitation");
        response.primaryMetric = JanitorI18n.text("janitor.fields.metric.note");
        response.labels.put("values", JanitorI18n.text("janitor.fields.inject.values"));
        response.labels.put("link", JanitorI18n.text("janitor.fields.inject.link"));
        response.labels.put("locked", JanitorI18n.text("janitor.fields.lockedShort"));
        if (result != null) {
            for (FieldUsage field : result.getFields()) {
                response.fields.add(new FieldSummaryDto(field, statusLabel(field)));
            }
            for (ScanProblem problem : result.getProblems()) {
                response.problems.add(new ProblemDto(problem));
            }
        }
        return Response.ok(response).build();
    }

    /** 필드 한 개의 상세. {@code id}는 숫자 ID 또는 {@code customfield_10001} 둘 다 받는다. */
    @GET
    @Path("/fields/{id}")
    public Response field(@PathParam("id") String id) {
        if (!AdminGuard.isAdmin()) {
            return forbidden();
        }
        ScanResult result = scanService.getLastResult();
        if (result == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Long numericId = parseFieldId(id);
        FieldUsage field = numericId == null ? null : result.getField(numericId);
        if (field == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new FieldDetailDto(field, statusLabel(field),
                JanitorI18n.text(field.getVerdictI18nKey()))).build();
    }

    /**
     * 스캔 시작. 이미 돌고 있으면 새로 시작하지 않고 409와 함께 현재 진행률을 준다
     * (기획서 9장의 중복 실행 방지).
     */
    @POST
    @Path("/scan")
    public Response scan(@QueryParam("lang") String lang) {
        if (!AdminGuard.isAdmin()) {
            return forbidden();
        }
        boolean started = scanService.startScan();
        return Response.status(started ? Response.Status.ACCEPTED : Response.Status.CONFLICT)
                .entity(status(lang))
                .build();
    }

    /**
     * @param lang 화면이 언어를 고정해 열려 있으면 그 값을 넘겨 같은 언어로 답하게 한다.
     *             비어 있으면 호출자의 Jira 로케일을 쓴다.
     */
    @GET
    @Path("/scan/status")
    public Response scanStatus(@QueryParam("lang") String lang) {
        if (!AdminGuard.isAdmin()) {
            return forbidden();
        }
        return Response.ok(status(lang)).build();
    }

    /** 전체 컬럼 CSV. 관리자가 스프레드시트로 정리 계획을 세운다(기획서 6.1). */
    @GET
    @Path("/fields.csv")
    @Produces("text/csv; charset=UTF-8")
    public Response csv() {
        if (!AdminGuard.isAdmin()) {
            return forbidden();
        }
        ScanResult result = scanService.getLastResult();
        if (result == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH);
        StringBuilder csv = new StringBuilder();
        // Excel이 UTF-8을 알아보게 BOM을 붙인다. 사내에서 대부분 Excel로 연다.
        csv.append('﻿');
        csv.append("status,name,fieldId,numericId,type,typeAvailable,locked,issuesWithValue,valueRows,")
                .append("lastValueChange,lastValueChangeAmbiguous,duplicateName,")
                .append("managed,screens,fieldConfigs,contexts,workflows,filters,")
                .append("permissionSchemes,notificationSchemes,issueSecuritySchemes,gadgets,columnLayouts,")
                .append("totalReferences,riskyReferences\n");

        for (FieldUsage field : result.getFields()) {
            FieldSummaryDto row = new FieldSummaryDto(field, statusLabel(field));
            csv.append(quote(row.status)).append(',')
                    .append(quote(row.name)).append(',')
                    .append(quote(row.fieldId)).append(',')
                    .append(row.id).append(',')
                    .append(quote(row.type)).append(',')
                    .append(row.typeAvailable).append(',')
                    .append(row.locked).append(',')
                    .append(row.issuesWithValue).append(',')
                    .append(row.valueRows).append(',')
                    .append(quote(row.lastValueChange)).append(',')
                    .append(row.lastValueChangeAmbiguous).append(',')
                    .append(row.duplicateName).append(',')
                    .append(row.managed).append(',')
                    .append(row.screens).append(',')
                    .append(row.fieldConfigs).append(',')
                    .append(row.contexts).append(',')
                    .append(row.workflows).append(',')
                    .append(row.filters).append(',')
                    .append(row.permissionSchemes).append(',')
                    .append(row.notificationSchemes).append(',')
                    .append(row.issueSecuritySchemes).append(',')
                    .append(row.gadgets).append(',')
                    .append(row.columnLayouts).append(',')
                    .append(row.totalReferences).append(',')
                    .append(row.riskyReferences).append('\n');
        }

        String filename = "custom-field-usage-" + stamp.format(result.getFinishedAt()) + ".csv";
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    private ScanStatusDto status() {
        return status(null);
    }

    private ScanStatusDto status(String lang) {
        ScanProgress progress = scanService.getProgress();
        String stageLabel = progress.getStage() == null
                ? null
                : JanitorI18n.text(progress.getStage().getI18nKey(), lang);
        return new ScanStatusDto(progress, scanService.getLastResult(), stageLabel);
    }

    private String statusLabel(FieldUsage field) {
        return JanitorI18n.text(field.getStatus().getI18nKey());
    }

    /** 권한 없는 요청은 403이고 본문에 이유를 담지 않는다(기획서 7.2). */
    private Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    static Long parseFieldId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("customfield_")) {
            value = value.substring("customfield_".length());
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * CSV 한 칸. 인젝션을 막고 큰따옴표를 이스케이프한다.
     *
     * <p>{@code = + - @} 뿐 아니라 <b>탭(0x09)과 CR(0x0D)로 시작하는 값도</b>
     * Excel / LibreOffice 가 수식으로 해석한다. 필드 이름은 관리자만 만들지만
     * 필터 이름은 아무 사용자나 만들고 그 이름이 CSV에 들어간다.
     */
    static String quote(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (startsWithFormulaTrigger(text)) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static boolean startsWithFormulaTrigger(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
    }
}
