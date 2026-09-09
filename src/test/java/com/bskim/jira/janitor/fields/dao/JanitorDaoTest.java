package com.bskim.jira.janitor.fields.dao;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OfBiz indicator 컬럼 해석.
 *
 * <p>실제로 밟은 함정이라 회귀 방지 가치가 크다(docs/00-환경실측.md 13번).
 * {@code managedconfigurationitem.managed} 는 boolean 이 아니라 {@code varchar(10)} 에
 * 담긴 {@code "true"} 다. SQL 에서 {@code setBoolean} 으로 비교하니 PostgreSQL 이
 * "operator does not exist: character varying = boolean" 으로 거부했다. 그래서 SQL
 * 비교를 없애고 Java 에서 판정한다.
 *
 * <p>이 판정이 거짓이 되면 잠긴 필드가 하나도 안 잡히고 Sprint / Epic Link 가
 * [미사용]로 찍힌다. 값 하나짜리 함수지만 라벨을 뒤집는 자리에 있다.
 */
public class JanitorDaoTest {

    @Test
    public void 참으로_읽는_표기들() {
        assertTrue(JanitorDao.isTrue("true"));
        assertTrue(JanitorDao.isTrue("TRUE"));
        assertTrue(JanitorDao.isTrue("True"));
        assertTrue(JanitorDao.isTrue("Y"));
        assertTrue(JanitorDao.isTrue("y"));
        assertTrue(JanitorDao.isTrue("t"));
        assertTrue(JanitorDao.isTrue("1"));
    }

    @Test
    public void 공백은_다듬는다() {
        // OfBiz 가 char(N) 컬럼에 넣으면 오른쪽이 공백으로 채워질 수 있다.
        assertTrue(JanitorDao.isTrue(" true "));
        assertTrue(JanitorDao.isTrue("Y  "));
    }

    @Test
    public void 거짓으로_읽는_표기들() {
        assertFalse(JanitorDao.isTrue(null));
        assertFalse(JanitorDao.isTrue(""));
        assertFalse(JanitorDao.isTrue("   "));
        assertFalse(JanitorDao.isTrue("false"));
        assertFalse(JanitorDao.isTrue("N"));
        assertFalse(JanitorDao.isTrue("0"));
        assertFalse(JanitorDao.isTrue("f"));
    }
}
