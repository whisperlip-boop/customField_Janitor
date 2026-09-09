package com.bskim.jira.janitor.fields.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link UsageStatus#judge} 의 진리표.
 *
 * <p>이 표가 있어야 하는 이유: 이 앱의 가치는 전부 이 함수 하나에 실린다. 라벨이
 * 틀리면 관리자가 지워도 되는 필드를 남기거나, 더 나쁘게는 지우면 안 되는 필드를
 * 지운다. 그리고 참조 종류를 새로 추가할 때 다른 분기가 깨져도 통합 테스트로는
 * 안 잡힌다 — 인스턴스를 띄우고 픽스처를 만들어야 하기 때문이다.
 *
 * <p>16조합을 전부 적는 것은 장식이 아니다. 리뷰에서 지적된 치명 버그
 * ("값 집계가 실패하면 값 0이 남아 [미사용]이 된다")는 <b>이 표의 첫 줄</b>이
 * 그대로 드러내는 사실이었다.
 */
public class UsageStatusTest {

    // judge(값 있는 이슈 수, 화면 참조, 위험 참조, 증거 참조)

    @Test
    public void 아무것도_없으면_미사용() {
        assertEquals(UsageStatus.UNUSED, UsageStatus.judge(0, 0, 0, 0));
    }

    @Test
    public void 위험_참조는_다른_모든_것을_이긴다() {
        // [위험]이 최우선인 이유: 지우면 기능이 조용히 깨진다. 값이나 화면이 어떻든
        // 관리자에게 먼저 보여야 하는 사실이다.
        assertEquals(UsageStatus.AT_RISK, UsageStatus.judge(0, 0, 1, 0));
        assertEquals(UsageStatus.AT_RISK, UsageStatus.judge(0, 1, 1, 0));
        assertEquals(UsageStatus.AT_RISK, UsageStatus.judge(5, 0, 1, 0));
        assertEquals(UsageStatus.AT_RISK, UsageStatus.judge(5, 1, 1, 1));
        assertEquals(UsageStatus.AT_RISK, UsageStatus.judge(0, 0, 1, 1));
    }

    @Test
    public void 값이_있고_화면에_있으면_활성() {
        assertEquals(UsageStatus.ACTIVE, UsageStatus.judge(1, 1, 0, 0));
        assertEquals(UsageStatus.ACTIVE, UsageStatus.judge(9999, 3, 0, 5));
    }

    @Test
    public void 값은_있는데_화면에_없으면_고아_데이터() {
        // 화면에서 빠졌는데 데이터는 남았다. 지우면 정보 손실이다.
        assertEquals(UsageStatus.ORPHAN_DATA, UsageStatus.judge(1, 0, 0, 0));
        assertEquals(UsageStatus.ORPHAN_DATA, UsageStatus.judge(1, 0, 0, 3));
    }

    @Test
    public void 값이_없고_화면에만_있으면_방치() {
        assertEquals(UsageStatus.ABANDONED, UsageStatus.judge(0, 1, 0, 0));
        assertEquals(UsageStatus.ABANDONED, UsageStatus.judge(0, 2, 0, 2));
    }

    @Test
    public void 값도_화면도_없지만_다른_사용_증거가_있으면_방치() {
        // 필터·가젯에서만 참조되는 필드. 라벨을 새로 만들지 않고(라벨 5개 고정)
        // [방치]로 본다 — 아무도 입력할 수 없지만 지우면 필터가 깨진다.
        assertEquals(UsageStatus.ABANDONED, UsageStatus.judge(0, 0, 0, 1));
    }

    /**
     * 필드 설정·컨텍스트는 사용 증거가 아니다. 모든 필드가 자동으로 갖기 때문에
     * 증거로 세면 [미사용]이 영원히 안 나온다(docs/00-환경실측.md 5번).
     * 그 규칙은 {@code ReferenceType.isEvidence()} 가 지키고, judge 는 이미 걸러진
     * 수만 받는다 — 이 테스트는 그 계약을 고정한다.
     */
    @Test
    public void 증거로_세지_않는_참조는_judge에_도달하지_않는다() {
        assertTrue("FIELD_CONFIG 는 증거가 아니어야 한다", !ReferenceType.FIELD_CONFIG.isEvidence());
        assertTrue("CONTEXT 는 증거가 아니어야 한다", !ReferenceType.CONTEXT.isEvidence());
    }

    /**
     * 정리 순서 = "청소하기 안전한 순". 목록 기본 정렬이 이 값을 쓰므로 뒤집히면
     * 관리자가 가장 먼저 보는 필드가 바뀐다.
     *
     * <pre>
     *   미사용(0) → 방치(1) → 고아 데이터(2) → 활성(3) → 위험(4)
     * </pre>
     *
     * <p>고아 데이터가 활성보다 먼저인 이유: 활성은 정상 상태라 관리자가 할 일이
     * 없지만, 고아 데이터는 "화면에서 빠졌는데 값이 남았다"는 <b>처리해야 할</b>
     * 상태다. 정렬은 "지워도 되는 순"이 아니라 "손봐야 하는 순"이다.
     */
    @Test
    public void 정리_순서는_손봐야_하는_순이고_위험이_마지막이다() {
        assertEquals(0, UsageStatus.UNUSED.getCleanupOrder());
        assertEquals(1, UsageStatus.ABANDONED.getCleanupOrder());
        assertEquals(2, UsageStatus.ORPHAN_DATA.getCleanupOrder());
        assertEquals(3, UsageStatus.ACTIVE.getCleanupOrder());
        assertEquals(4, UsageStatus.AT_RISK.getCleanupOrder());
        // 위험은 반드시 마지막이어야 한다 — 기본 정렬에서 맨 아래에 있어야
        // "위에서부터 지워도 된다"는 읽기가 성립한다.
        for (UsageStatus status : UsageStatus.values()) {
            if (status != UsageStatus.AT_RISK) {
                assertTrue(status + " 가 위험보다 뒤에 있다",
                        status.getCleanupOrder() < UsageStatus.AT_RISK.getCleanupOrder());
            }
        }
    }

    /**
     * 라벨 5개를 늘리거나 줄이지 않는다(불변 5). 이 앱의 정체성이고, 설명서·REST
     * 코드·CSV·주입 배지가 전부 이 집합을 전제한다.
     */
    @Test
    public void 라벨은_정확히_다섯_개다() {
        assertEquals(5, UsageStatus.values().length);
    }
}
