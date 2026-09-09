package com.bskim.jira.janitor.fields.scan;

import com.bskim.jira.janitor.fields.model.ScanProgress;

/**
 * 참조 수집기 하나. 전체 설정을 한 번 훑으며 {@link ScanContext}에 참조를 붙인다.
 *
 * <p>수집기는 예외를 밖으로 던져도 된다. 스캔 루프가 잡아서 "확인 불가"로 기록하고
 * 다음 단계를 계속한다 — 한 단계가 실패해도 나머지 정보는 여전히 쓸모가 있다.
 * 단 {@link #isEssential()} 이 참인 수집기는 예외다(아래).
 */
public interface ReferenceCollector {

    /** 진행률 보고에 쓰는 단계. */
    ScanProgress.Stage getStage();

    void collect(ScanContext context);

    /**
     * 이 수집기가 실패하면 스캔 전체를 실패시켜야 하는가.
     *
     * <p>기본은 거짓 — 참조 하나가 안 잡히면 수가 줄어들 뿐이고, 부분 결과라도
     * 관리자에게 쓸모가 있다.
     *
     * <p>참을 돌려주는 것은 <b>실패가 라벨을 뒤집는</b> 수집기다. 예를 들어 잠긴 필드
     * 조회가 실패하면 Sprint / Epic Link 가 [위험]이 아니라 [미사용]로 찍히고 삭제
     * 링크가 열린다 — 없는 정보가 "삭제해도 된다"는 적극적인 신호로 바뀐다.
     * 그런 단계는 조용히 넘기는 것보다 스캔을 실패시키는 쪽이 안전하다.
     *
     * @see ScanFailedException
     */
    boolean isEssential();
}
