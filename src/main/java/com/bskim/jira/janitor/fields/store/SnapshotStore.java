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
 * 이미 provided 로 쓰고 있다. 새로 들이는 것이 없다.
 */
public final class SnapshotStore {

    private static final Logger log = Logger.getLogger(SnapshotStore.class);

    /** 일반 스캔 결과. 플러그인 키를 접두사로 둬서 남의 설정과 섞이지 않게 한다. */
    public static final String SCAN_KEY = "com.bskim.jira.janitor.fields.snapshot";

    /** 심층 스캔 결과. 따로 둔다 — 두 스캔은 서로 다른 때에 끝나고 수명도 다르다. */
    public static final String DEEP_KEY = "com.bskim.jira.janitor.fields.deepSnapshot";

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

    /** 저장. 실패는 로그만 남기고 삼킨다 — 스냅샷 때문에 스캔이 죽으면 안 된다. */
    public void save(String json) {
        try {
            PluginSettings settings = settings();
            if (settings != null) {
                settings.put(key, json);
            }
        } catch (Throwable t) {
            log.warn("스냅샷 저장 실패", t);
        }
    }

    /** 읽기. 없거나 실패면 null. */
    public String load() {
        try {
            PluginSettings settings = settings();
            if (settings == null) {
                return null;
            }
            Object value = settings.get(key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            log.warn("스냅샷 읽기 실패", t);
            return null;
        }
    }

    /** 지우기. 스키마가 안 맞는 스냅샷을 버릴 때 쓴다. */
    public void clear() {
        try {
            PluginSettings settings = settings();
            if (settings != null) {
                settings.remove(key);
            }
        } catch (Throwable t) {
            log.warn("스냅샷 삭제 실패", t);
        }
    }
}
