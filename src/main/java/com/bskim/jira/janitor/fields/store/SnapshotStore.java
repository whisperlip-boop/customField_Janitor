package com.bskim.jira.janitor.fields.store;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import org.apache.log4j.Logger;

/**
 * 스캔 스냅샷을 담아 두는 곳. 문자열 하나를 넣고 빼는 것이 전부다.
 *
 * <p>왜 ActiveObjects 가 아닌가: AO 의 {@code ActiveObjects} 서비스는 플러그인마다
 * 다른 인스턴스를 주는 OSGi {@code ServiceFactory} 라서 {@code ComponentAccessor} 로
 * 꺼내면 우리 것이 아니다. 제대로 주입받으려면 spring-scanner 를 들여야 하는데,
 * 그러면 지금 {@code AdminOnlyCondition} 이 기대고 있는 Spring 부트스트랩
 * (빈 없는 {@code plugin-context.xml} + {@code Spring-Context *})을 다시 배선해야 한다.
 * 조용히 깨지기로 이미 한 번 당한 자리다(docs/00 9번). "문서 하나를 기억한다"는
 * 요구에 그 위험을 살 이유가 없다.
 *
 * <p>SAL 의 {@code PluginSettings} 는 Jira 의 PropertySet 에 얹혀 있고 sal-api 는
 * 이미 provided 로 쓰고 있다. 새로 들이는 것이 없다. 값이 길면 {@code propertytext},
 * 짧으면 {@code propertystring} 에 들어간다 — SAL 이 길이를 보고 고른다(docs/00 33번).
 */
public final class SnapshotStore {

    private static final Logger log = Logger.getLogger(SnapshotStore.class);

    /** 일반 스캔 결과. 플러그인 키를 접두사로 둬서 남의 설정과 섞이지 않게 한다. */
    public static final String SCAN_KEY = "com.bskim.jira.janitor.fields.snapshot";

    /** 심층 스캔 결과. 따로 둔다 — 두 스캔은 서로 다른 때에 끝나고 수명도 다르다. */
    public static final String DEEP_KEY = "com.bskim.jira.janitor.fields.deepSnapshot";

    /**
     * 읽기 결과. <b>"없다"와 "못 읽었다"를 가른다.</b>
     *
     * <p>둘을 같은 null 로 돌려주면 부르는 쪽이 "스냅샷이 없구나"로 잠그고 다시 읽지
     * 않는다. 재기동 직후 SAL 서비스가 아직 안 떠서 못 읽은 것이었다면, 그 뒤의 첫
     * 스캔이 실패할 때 <b>좋은 스냅샷 위에 빈 결과를 덮어쓴다</b>(리뷰 지적 — 34번의
     * "쓰기 전에 읽는다"가 "읽었는데 못 찾았다"로 우회된다).
     */
    public static final class Loaded {

        /** 저장소에 닿았는가. 거짓이면 {@link #json} 은 의미가 없고 다시 읽어야 한다. */
        public final boolean reachable;
        /** 저장된 값. 닿았는데 없으면 null. */
        public final String json;

        Loaded(boolean reachable, String json) {
            this.reachable = reachable;
            this.json = json;
        }

        public boolean isEmpty() {
            return json == null || json.trim().isEmpty();
        }
    }

    private final String key;

    public SnapshotStore(String key) {
        this.key = key;
    }

    /**
     * SAL 의 설정 저장소. 플러그인 컨테이너에 주입하지 않고 OSGi 서비스로 직접 꺼낸다
     * ({@code Atlassian-Plugin-Key} 가 설정돼 있어 XML component-import 를 못 쓴다).
     *
     * @return 못 얻으면 null. 스냅샷은 부가 기능이므로 없다고 스캔을 막지 않는다.
     */
    private static PluginSettings settings() {
        PluginSettingsFactory factory =
                ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
        if (factory == null) {
            // 8.13 과 8.17.1 에서 어느 경로가 통하는지 실측으로 정하기 전까지 둘 다 본다.
            factory = ComponentAccessor.getComponent(PluginSettingsFactory.class);
            if (factory != null) {
                // WARN 으로 남긴다. 이 인스턴스들은 우리 패키지의 INFO 를 버린다(실측)
                // — INFO 로 두면 "어느 경로로 얻었나"를 영영 못 본다. 8.13 에서는
                // OSGi 경로가 통했으므로 이 줄이 보이면 8.17.1 이 다르다는 뜻이다.
                log.warn("PluginSettingsFactory 를 getComponent 경로로 얻었다 (OSGi 경로 실패)");
            }
        }
        if (factory == null) {
            log.warn("PluginSettingsFactory 를 못 얻었다 — 스냅샷 저장을 건너뛴다");
            return null;
        }
        return factory.createGlobalSettings();
    }

    /**
     * 저장.
     *
     * @return 실제로 썼으면 true. 실패는 로그만 남기고 삼킨다 — 스냅샷 때문에 스캔이
     *         죽으면 안 된다.
     */
    public boolean save(String json) {
        try {
            PluginSettings settings = settings();
            if (settings == null) {
                return false;
            }
            settings.put(key, json);
            return true;
        } catch (Throwable t) {
            log.warn("스냅샷 저장 실패", t);
            return false;
        }
    }

    /** 읽기. 저장소에 못 닿으면 {@code reachable=false} 다 — 그때는 잠그지 말고 다시 읽어야 한다. */
    public Loaded load() {
        try {
            PluginSettings settings = settings();
            if (settings == null) {
                return new Loaded(false, null);
            }
            Object value = settings.get(key);
            return new Loaded(true, value instanceof String ? (String) value : null);
        } catch (Throwable t) {
            log.warn("스냅샷 읽기 실패", t);
            return new Loaded(false, null);
        }
    }
}
