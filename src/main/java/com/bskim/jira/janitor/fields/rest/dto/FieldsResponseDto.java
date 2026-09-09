package com.bskim.jira.janitor.fields.rest.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /fields} 응답.
 *
 * <p>{@code limitation}을 응답에 넣는 이유: 기획서 5.4·함정 8. 심층 스캔이 없는
 * v1에서 "참조 없음 = 삭제 안전"으로 읽히면 관리자가 잘못된 확신으로 필드를 지운다.
 * 도구가 스스로 한계를 말해야 한다. 화면과 REST 양쪽에 같은 문구를 둔다.
 */
public class FieldsResponseDto {

    public ScanStatusDto scan;
    public List<FieldSummaryDto> fields = new ArrayList<FieldSummaryDto>();
    public List<ProblemDto> problems = new ArrayList<ProblemDto>();
    public String limitation;
    public String primaryMetric;

    /**
     * 커스텀 필드 관리 화면 주입 JS가 쓰는 번역 문구(기획서 6.3).
     * 서버에서 넣어주면 주입 스크립트가 i18n 번들을 따로 들 필요가 없다.
     */
    public Map<String, String> labels = new LinkedHashMap<String, String>();
}
