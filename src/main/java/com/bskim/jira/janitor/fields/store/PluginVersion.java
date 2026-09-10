package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.plugin.Plugin;
import org.apache.log4j.Logger;

/**
 * 실행 중인 플러그인 버전. 스냅샷의 판을 가르는 값이다.
 *
 * <p>한 곳에만 둔다. v1.2.1 에서 {@code ScanService} 에 "버전을 못 읽으면 스냅샷을 쓰지
 * 않는다" 가드를 넣었는데, 같은 코드를 복사해 만든 {@code DeepScanService} 에는 그 가드가
 * 없었다(리뷰 지적). 안전 규칙이 두 벌이면 한쪽만 적용된다.
 *
 * <p>버전이 다르면 스냅샷을 버리는 이유: 수집기가 늘어난 새 버전이 옛 스냅샷을 읽으면
 * 그 참조가 통째로 빠진 채 표가 그려지고 라벨이 뒤집힌다(docs/00 15번).
 * 버전을 못 읽으면(unknown) 쓸 때도 읽을 때도 같은 값이라 판 검사가 무력해지므로
 * <b>그때는 스냅샷을 아예 쓰지 않는다</b>(34번).
 */
public final class PluginVersion {

    private static final Logger log = Logger.getLogger(PluginVersion.class);

    public static final String PLUGIN_KEY = "com.bskim.jira.janitor";

    /** 버전을 못 읽었을 때의 값. 이 값이면 스냅샷을 읽지도 쓰지도 않는다. */
    public static final String UNKNOWN = "unknown";

    /** 실제 버전 공급자. 러너가 이걸 받고, 테스트는 고정 문자열을 준다. */
    public static final VersionSource SOURCE = new VersionSource() {
        @Override
        public String current() {
            return PluginVersion.current();
        }
    };

    private PluginVersion() {
    }

    public static String current() {
        try {
            Plugin plugin = ComponentAccessor.getPluginAccessor().getPlugin(PLUGIN_KEY);
            if (plugin != null && plugin.getPluginInformation() != null) {
                String version = plugin.getPluginInformation().getVersion();
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (Throwable t) {
            log.debug("플러그인 버전을 못 읽었다", t);
        }
        return UNKNOWN;
    }

    public static boolean isKnown(String version) {
        return version != null && !UNKNOWN.equals(version);
    }
}
