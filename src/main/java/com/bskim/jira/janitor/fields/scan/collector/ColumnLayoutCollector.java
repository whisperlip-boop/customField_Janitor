package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.user.ApplicationUser;
import com.bskim.jira.janitor.fields.dao.ColumnLayoutRow;
import com.bskim.jira.janitor.fields.dao.JanitorDao;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 이슈 네비게이터 컬럼 설정 참조 (기획서 5.3(9)).
 *
 * <p>컬럼 설정은 셋 중 하나다 — 시스템 기본, 필터의 컬럼, 사용자 개인 설정
 * ({@link ColumnLayoutRow} 참고). 셋을 한 참조 종류로 묶되 화면에서는 구분한다.
 * 조치가 다르기 때문이다: 기본 컬럼은 관리자가, 필터 컬럼은 필터 소유자가,
 * 개인 설정은 각자가 바꾼다.
 *
 * <p><b>개인 설정은 필드마다 한 건으로 합친다.</b> 사용자 수만큼 참조를 만들면
 * 인기 있는 필드 하나에 수백 행이 생겨 상세 화면이 못 쓰게 되고, 관리자가 할 수
 * 있는 일도 없다(남의 개인 설정은 관리 화면에서 못 고친다). 대신 몇 명인지와
 * 이름 일부를 부가 정보로 남긴다.
 *
 * <p>시스템 기본 컬럼에는 관리 화면 링크를 붙이지 않는다. 실측(8.13)에서
 * {@code ViewIssueColumns!default.jspa} 를 포함한 후보가 전부 404였다 —
 * 없는 링크를 붙이면 관리자가 그 화면을 찾는 데 시간을 쓴다.
 */
public class ColumnLayoutCollector implements ReferenceCollector {

    /** 부가 정보에 이름을 몇 명까지 적을지. 나머지는 "+N" 으로 줄인다. */
    private static final int NAMES_SHOWN = 5;

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.COLUMN_LAYOUTS;
    }

    @Override
    public void collect(ScanContext context) {
        collect(context, new JanitorDao().getColumnLayoutRows());
    }

    /** 테스트 진입점. DAO 없이 행을 직접 넣는다. */
    void collect(ScanContext context, List<ColumnLayoutRow> rows) {
        // 필드 → 그 필드를 쓰는 개인 설정의 주인들. 마지막에 한 건으로 합친다.
        Map<FieldUsage, TreeSet<String>> personalOwners =
                new LinkedHashMap<FieldUsage, TreeSet<String>>();

        for (ColumnLayoutRow row : rows) {
            FieldUsage field = context.byFieldId(row.getFieldIdentifier());
            if (field == null) {
                // 필드가 지워졌는데 컬럼 설정만 남은 행. 판정 대상이 아니다.
                continue;
            }

            if (row.getUserKey() != null) {
                TreeSet<String> owners = personalOwners.get(field);
                if (owners == null) {
                    owners = new TreeSet<String>();
                    personalOwners.put(field, owners);
                }
                owners.add(displayName(row.getUserKey()));
                continue;
            }

            if (row.getFilterId() != null) {
                String name = row.getFilterName() != null
                        ? row.getFilterName()
                        : "(filter " + row.getFilterId() + ")";
                context.addReference(field, new Reference(
                        ReferenceType.COLUMN_LAYOUT,
                        name,
                        "filter:" + row.getFilterId(),
                        null,
                        "janitor.fields.ref.columnLayout.filter",
                        AdminUrls.filter(row.getFilterId()),
                        Collections.<String>emptyList(),
                        ReferenceType.COLUMN_LAYOUT.isRisky()));
                continue;
            }

            // 시스템 기본. 대상 이름이 데이터에 없으므로 화면이 i18n 키로 그린다.
            context.addReference(field, new Reference(
                    ReferenceType.COLUMN_LAYOUT,
                    null,
                    "system:" + row.getLayoutId(),
                    null,
                    "janitor.fields.ref.columnLayout.system",
                    null,
                    Collections.<String>emptyList(),
                    ReferenceType.COLUMN_LAYOUT.isRisky()));
        }

        for (Map.Entry<FieldUsage, TreeSet<String>> entry : personalOwners.entrySet()) {
            context.addReference(entry.getKey(), new Reference(
                    ReferenceType.COLUMN_LAYOUT,
                    null,
                    "personal",
                    summarize(entry.getValue()),
                    "janitor.fields.ref.columnLayout.personal",
                    null,
                    Collections.<String>emptyList(),
                    ReferenceType.COLUMN_LAYOUT.isRisky()));
        }
    }

    /** "3 · bskim, janitor-tester, +1" — 몇 명인지 먼저, 그다음 이름 일부. */
    private static String summarize(TreeSet<String> owners) {
        List<String> shown = new ArrayList<String>(owners).subList(
                0, Math.min(NAMES_SHOWN, owners.size()));
        StringBuilder text = new StringBuilder();
        text.append(owners.size());
        if (!shown.isEmpty()) {
            text.append(" · ");
            for (int i = 0; i < shown.size(); i++) {
                text.append(i == 0 ? "" : ", ").append(shown.get(i));
            }
        }
        if (owners.size() > shown.size()) {
            text.append(", +").append(owners.size() - shown.size());
        }
        return text.toString();
    }

    /**
     * {@code columnlayout.username} 은 사용자 <b>키</b>다(실측: {@code JIRAUSER10000}).
     * {@code searchrequest.authorname} 과 같은 함정이라 같은 방식으로 푼다.
     *
     * <p>조회 실패는 표시 문제일 뿐이므로 키를 그대로 낸다. 여기서 예외가 새면
     * 수집기 전체가 "확인 불가"가 되어 참조가 통째로 사라진다.
     */
    private static String displayName(String userKey) {
        try {
            ApplicationUser user = ComponentAccessor.getUserManager().getUserByKey(userKey);
            if (user != null && user.getDisplayName() != null) {
                return user.getDisplayName();
            }
        } catch (RuntimeException e) {
            // 아래에서 키를 그대로 쓴다.
        }
        return userKey;
    }

    @Override
    public boolean isEssential() {
        return false;
    }
}
