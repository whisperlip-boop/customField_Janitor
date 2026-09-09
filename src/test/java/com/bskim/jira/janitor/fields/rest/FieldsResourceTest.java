package com.bskim.jira.janitor.fields.rest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * REST 입구의 순수 함수 두 개. Jira 없이 돌아간다.
 *
 * <p>{@code parseFieldId} 는 사용자가 준 문자열을 필드 ID로 바꾸는 유일한 자리다.
 * 모든 처리가 ID 기준(불변 2)이므로 여기가 틀리면 잘못된 필드의 정보를 보여준다.
 *
 * <p>{@code quote} 는 CSV로 나가는 모든 값을 통과시킨다. 필터 이름은 아무 사용자나
 * 만들 수 있고 그게 관리자의 Excel에서 열린다.
 */
public class FieldsResourceTest {

    @Test
    public void 숫자만_준_경우() {
        assertEquals(Long.valueOf(10001), FieldsResource.parseFieldId("10001"));
    }

    @Test
    public void customfield_접두사가_붙은_경우() {
        assertEquals(Long.valueOf(10001), FieldsResource.parseFieldId("customfield_10001"));
    }

    @Test
    public void 공백은_다듬는다() {
        assertEquals(Long.valueOf(10001), FieldsResource.parseFieldId("  10001  "));
    }

    @Test
    public void 유효하지_않은_입력은_전부_null이다() {
        // null 을 주면 호출부가 404를 낸다. 예외를 던지면 500이 되고, 관리자는
        // "도구가 깨졌다"로 읽는다 — 잘못된 URL을 준 것과 다른 이야기다.
        assertNull(FieldsResource.parseFieldId(null));
        assertNull(FieldsResource.parseFieldId(""));
        assertNull(FieldsResource.parseFieldId("   "));
        assertNull(FieldsResource.parseFieldId("customfield_"));
        assertNull(FieldsResource.parseFieldId("customfield_abc"));
        assertNull(FieldsResource.parseFieldId("abc"));
        assertNull(FieldsResource.parseFieldId("10001; DROP TABLE customfield"));
        assertNull(FieldsResource.parseFieldId("\"><img src=x onerror=alert(1)>"));
    }

    @Test
    public void long_범위를_넘으면_null이다() {
        assertNull(FieldsResource.parseFieldId("99999999999999999999"));
    }

    @Test
    public void 평범한_값은_따옴표만_붙인다() {
        assertEquals("\"Story Points\"", FieldsResource.quote("Story Points"));
        assertEquals("\"10001\"", FieldsResource.quote(10001L));
        assertEquals("", FieldsResource.quote(null));
    }

    @Test
    public void 큰따옴표는_두_번으로_이스케이프한다() {
        assertEquals("\"say \"\"hi\"\"\"", FieldsResource.quote("say \"hi\""));
    }

    @Test
    public void 수식으로_시작하는_값은_따옴표를_앞에_붙인다() {
        assertEquals("\"'=SUM(A1)\"", FieldsResource.quote("=SUM(A1)"));
        assertEquals("\"'+1\"", FieldsResource.quote("+1"));
        assertEquals("\"'-1\"", FieldsResource.quote("-1"));
        assertEquals("\"'@foo\"", FieldsResource.quote("@foo"));
    }

    /** 리뷰 19번: 탭과 CR도 Excel이 수식으로 해석한다. */
    @Test
    public void 탭과_CR로_시작하는_값도_막는다() {
        assertEquals("\"'\t=SUM(A1)\"", FieldsResource.quote("\t=SUM(A1)"));
        assertEquals("\"'\r=SUM(A1)\"", FieldsResource.quote("\r=SUM(A1)"));
    }

    @Test
    public void 줄바꿈이_들어간_값은_따옴표_안에_그대로_둔다() {
        // RFC 4180: 따옴표 안의 줄바꿈은 유효하다. 지우면 데이터가 바뀐다.
        assertEquals("\"두\n줄\"", FieldsResource.quote("두\n줄"));
    }
}
