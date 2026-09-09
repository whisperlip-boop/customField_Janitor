package com.bskim.jira.janitor.fields.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// 참고: JUnit 4.10 에는 assertNotEquals 가 없다(4.11부터). 오프라인 빌드를 유지하려고
// 버전을 올리지 않고 assertFalse(a.equals(b)) 로 쓴다.

/**
 * {@link Reference#equals} 의 계약.
 *
 * <p>이 테스트가 우선순위 1인 이유: {@code equals} 가 너무 관대해지면
 * {@link FieldUsage#addReference} 가 <b>참조를 조용히 버린다.</b> 로그도, "확인 불가"
 * 목록도 남지 않는다 — 이 앱이 가장 싫어하는 실패 방식이다(불변 7).
 *
 * <p>실제로 v1.0.1 에서 그 일이 일어났다. 스킴 참조의 동일성 키에 권한 구분이 없어서
 * 한 스킴이 같은 필드로 세 권한을 주면 3건이 1건으로 합쳐졌다. 그 회귀를 막는 것이
 * 아래 {@code 스킴의_권한이_다르면_다른_참조다} 다.
 */
public class ReferenceTest {

    private static Reference ref(ReferenceType type, String name, String id, String detail) {
        return new Reference(type, name, id, detail, null);
    }

    @Test
    public void 같은_값이면_같은_참조다() {
        Reference a = ref(ReferenceType.SCREEN, "Default Screen", "10000", "Create");
        Reference b = ref(ReferenceType.SCREEN, "Default Screen", "10000", "Create");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void 종류가_다르면_다른_참조다() {
        assertFalse(ref(ReferenceType.SCREEN, "n", "1", "d")
                .equals(ref(ReferenceType.FILTER, "n", "1", "d")));
    }

    @Test
    public void 대상_ID가_다르면_다른_참조다() {
        assertFalse(ref(ReferenceType.SCREEN, "n", "1", "d")
                .equals(ref(ReferenceType.SCREEN, "n", "2", "d")));
    }

    @Test
    public void 상세가_다르면_다른_참조다() {
        // 같은 화면의 Create 탭과 Edit 탭은 서로 다른 참조다.
        assertFalse(ref(ReferenceType.SCREEN, "n", "1", "Create")
                .equals(ref(ReferenceType.SCREEN, "n", "1", "Edit")));
    }

    /**
     * v1.0.1 회귀 방지. 스킴 참조는 {@code targetId} 에 스킴 ID와 권한/이벤트 ID를
     * 함께 넣는다. 넣지 않으면 Browse / Edit / Assign 세 권한의 키가 완전히 같아져
     * 중복 제거가 3건을 1건으로 합치고, 상세 화면에서 "세 군데가 걸려 있다"는 사실이
     * 사라진다. [위험] 필드의 상세는 관리자가 무엇을 손봐야 하는지 읽는 유일한 곳이다.
     */
    @Test
    public void 스킴의_권한이_다르면_다른_참조다() {
        Reference browse = ref(ReferenceType.PERMISSION_SCHEME, "Default Permission Scheme", "0/10", "userCF");
        Reference edit = ref(ReferenceType.PERMISSION_SCHEME, "Default Permission Scheme", "0/11", "userCF");
        Reference assign = ref(ReferenceType.PERMISSION_SCHEME, "Default Permission Scheme", "0/13", "userCF");
        assertFalse(browse.equals(edit));
        assertFalse(edit.equals(assign));
        assertFalse(browse.equals(assign));
    }

    /**
     * 위험도가 다르면 다른 참조다. 같은 워크플로 안에서도 전이 화면 참조와 조건 참조는
     * 위험도가 다르고, 관리자에게 다른 이야기다 — 전이 화면에서 필드가 빠지는 것은
     * 기능이 조용히 깨지는 것과 다르다.
     */
    @Test
    public void 위험도가_다르면_다른_참조다() {
        Reference risky = new Reference(ReferenceType.WORKFLOW, "wf", "1", "transition: Start",
                null, null, null, true);
        Reference safe = new Reference(ReferenceType.WORKFLOW, "wf", "1", "transition: Start",
                null, null, null, false);
        assertFalse(risky.equals(safe));
    }

    /**
     * {@code projects} 와 {@code adminUrl} 은 비교에 넣지 않는다. 같은 대상에서 파생된
     * 값이라 판단에 새 정보를 주지 않고, 넣으면 목록 순서 차이만으로 중복이 살아남는다.
     *
     * <p>이 테스트가 없으면 나중에 누가 "projects 도 비교에 넣자"로 되돌린다.
     * 그러면 같은 참조가 프로젝트 순서만 다르게 두 번 세진다.
     */
    @Test
    public void 프로젝트_목록과_관리URL은_동일성에_영향이_없다() {
        Reference a = new Reference(ReferenceType.SCREEN, "n", "1", "d", null,
                "/secure/admin/A.jspa", Arrays.asList("PROJ", "OTHER"), false);
        Reference b = new Reference(ReferenceType.SCREEN, "n", "1", "d", null,
                "/secure/admin/B.jspa", Arrays.asList("OTHER", "PROJ"), false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_계약을_지킨다() {
        Reference a = ref(ReferenceType.SCREEN, "n", "1", "d");
        Reference b = ref(ReferenceType.SCREEN, "n", "1", "d");
        assertTrue("재귀성", a.equals(a));
        assertTrue("대칭성", a.equals(b) && b.equals(a));
        assertFalse("null", a.equals(null));
        assertFalse("다른 타입", a.equals("문자열"));
    }

    @Test
    public void null_필드가_있어도_안전하다() {
        Reference a = ref(ReferenceType.MANAGED, null, null, null);
        Reference b = ref(ReferenceType.MANAGED, null, null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(ref(ReferenceType.MANAGED, "name", null, null)));
    }

    /** 중복 제거가 실제로 걸리는지 — addReference 입구까지 확인한다. */
    @Test
    public void 같은_참조를_두_번_넣으면_한_건이다() {
        FieldUsage field = new FieldUsage(10001L, "Test", "type", "Type");
        field.addReference(ref(ReferenceType.GADGET, "가젯", "99001", "xstattype"));
        field.addReference(ref(ReferenceType.GADGET, "가젯", "99001", "xstattype"));
        assertEquals(1, field.getReferences().size());
    }

    /**
     * 2차원 통계 가젯이 x축과 y축에서 같은 필드를 쓰면 <b>두 건</b>이어야 한다.
     * 중복 제거가 이걸 합치면 안 된다 — detail 이 다르므로 다른 참조다.
     */
    @Test
    public void 한_가젯이_두_자리에서_쓰면_두_건이다() {
        FieldUsage field = new FieldUsage(10001L, "Test", "type", "Type");
        field.addReference(ref(ReferenceType.GADGET, "가젯", "99001", "xstattype"));
        field.addReference(ref(ReferenceType.GADGET, "가젯", "99001", "ystattype"));
        assertEquals(2, field.getReferences().size());
    }

    @Test
    public void null_참조는_무시한다() {
        FieldUsage field = new FieldUsage(10001L, "Test", "type", "Type");
        field.addReference(null);
        assertEquals(Collections.emptyList(), field.getReferences());
    }
}
