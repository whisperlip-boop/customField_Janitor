package com.bskim.jira.janitor.fields.store;

/**
 * 스냅샷 문자열 하나를 넣고 빼는 곳. 실제 구현은 {@link SnapshotStore}(SAL PluginSettings),
 * 테스트는 가짜를 준다 — 러너의 복원·저장 규칙을 Jira 없이 검증하기 위해서다.
 */
public interface SnapshotBackend {

    /** 읽기. 저장소에 못 닿으면 {@code reachable=false} — 그때는 잠그지 말고 다시 읽어야 한다. */
    SnapshotStore.Loaded load();

    /** @return 실제로 썼으면 true. 실패는 로그만 남기고 삼킨다 — 스냅샷 때문에 스캔이 죽으면 안 된다. */
    boolean save(String json);
}
