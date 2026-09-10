package com.bskim.jira.janitor.fields.store;

/**
 * 지금 도는 플러그인의 버전을 주는 곳. 실제는 {@link PluginVersion#SOURCE}(PluginAccessor),
 * 테스트는 고정 문자열을 준다 — 테스트 JVM 에는 ComponentAccessor 가 없어 항상
 * {@link PluginVersion#UNKNOWN} 이 되고, 그러면 복원 경로가 저장소를 보기도 전에 끝난다.
 */
public interface VersionSource {

    String current();
}
