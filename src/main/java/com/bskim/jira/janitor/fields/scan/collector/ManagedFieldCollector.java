package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.dao.ManagedFieldRow;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;

import java.util.Map;

/**
 * 앱이 관리하는(잠긴) 필드를 참조로 등록한다.
 *
 * <p>왜 필요한지는 {@link ReferenceType#MANAGED} 주석에 있다. 요약하면 Sprint / Epic Link /
 * Rank 같은 앱 소유 필드가 값 0 · 화면 0으로 보여서 [미사용]으로 찍히는 것을 막는다.
 *
 * <p>가장 먼저 도는 수집기다. 뒤쪽 수집기가 실패해도 이 라벨은 살아 있어야 한다.
 */
public class ManagedFieldCollector implements ReferenceCollector {

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.MANAGED;
    }

    @Override
    public void collect(ScanContext context) {
        JanitorDao dao = new JanitorDao();
        Map<Long, ManagedFieldRow> managed = dao.getManagedCustomFields();

        for (FieldUsage field : context.getFields()) {
            ManagedFieldRow row = managed.get(field.getNumericId());
            if (row == null) {
                continue;
            }
            context.addReference(field, new Reference(
                    ReferenceType.MANAGED,
                    pluginName(row.getPluginKey()),
                    row.getPluginKey(),
                    row.getAccessLevel(),
                    null,
                    // 갈 수 있는 관리 화면이 없다. 앱을 지우는 것 말고는 관리자가 할 게 없다.
                    null,
                    null,
                    row.isLocked()));
        }
    }

    /**
     * 플러그인 키를 표시 이름으로 바꾼다. {@code PluginAccessor.getPlugin()}은 앱이
     * 비활성이어도 설치돼 있으면 객체를 돌려준다 — 잠긴 필드 대부분이 그 상태다.
     */
    private String pluginName(String pluginKey) {
        if (pluginKey == null) {
            return "?";
        }
        try {
            PluginAccessor pluginAccessor = ComponentAccessor.getPluginAccessor();
            Plugin plugin = pluginAccessor == null ? null : pluginAccessor.getPlugin(pluginKey);
            if (plugin != null && plugin.getName() != null) {
                return plugin.getName();
            }
        } catch (RuntimeException e) {
            // 이름 조회 실패는 표시 문제일 뿐이다. 키를 그대로 낸다.
        }
        return pluginKey;
    }
}
