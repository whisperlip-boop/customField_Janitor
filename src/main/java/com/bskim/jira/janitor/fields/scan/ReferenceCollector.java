package com.bskim.jira.janitor.fields.scan;

import com.bskim.jira.janitor.fields.model.ScanProgress;

/**
 * 참조 수집기 하나. 전체 설정을 한 번 훑으며 {@link ScanContext}에 참조를 붙인다.
 *
 * <p>수집기는 예외를 밖으로 던져도 된다. 스캔 루프가 잡아서 "확인 불가"로 기록하고
 * 다음 단계를 계속한다 — 한 단계가 실패해도 나머지 정보는 여전히 쓸모가 있다.
 */
public interface ReferenceCollector {

    /** 진행률 보고에 쓰는 단계. */
    ScanProgress.Stage getStage();

    void collect(ScanContext context);
}
