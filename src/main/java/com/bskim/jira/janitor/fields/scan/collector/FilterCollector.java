package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.jql.parser.JqlQueryParser;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.query.Query;
import com.atlassian.query.clause.Clause;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.order.SearchSort;
import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ReferenceType;
import com.bskim.jira.janitor.fields.model.ScanProgress;
import com.bskim.jira.janitor.fields.scan.ReferenceCollector;
import com.bskim.jira.janitor.fields.scan.ScanContext;
import org.ofbiz.core.entity.GenericValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장된 검색(필터) 참조 (기획서 5.3(6)).
 *
 * <p>전체 필터를 {@code OfBizDelegator.findAll("SearchRequest")}로 한 번에 읽는다.
 * 엔티티 필드명은 실측했다: {@code name}=filtername, {@code author}=authorname,
 * {@code request}=reqcontent(JQL 본문).
 *
 * <p>JQL은 필드를 <b>이름</b>으로도({@code "Story Points" is not EMPTY})
 * <b>ID</b>로도({@code cf[10001] is not EMPTY}) 참조한다. 둘 다 잡아야 한다(함정 4).
 * ORDER BY 절도 참조다.
 *
 * <p>파싱에 실패한 필터는 버리지 않는다. "확인 불가" 목록에 올리고, 그와 별개로
 * 문자열 수준의 최선 탐색까지 해본다 — 조용히 누락되면 관리자가 이 도구를 근거로
 * 필드를 지웠을 때 필터가 깨진다(함정 7).
 */
public class FilterCollector implements ReferenceCollector {

    /** JQL의 {@code cf[10001]} 표기. */
    private static final Pattern CF_BRACKET = Pattern.compile("cf\\s*\\[\\s*(\\d+)\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    @Override
    public ScanProgress.Stage getStage() {
        return ScanProgress.Stage.FILTERS;
    }

    @Override
    public void collect(ScanContext context) {
        OfBizDelegator delegator = ComponentAccessor.getOfBizDelegator();
        JqlQueryParser parser = ComponentAccessor.getComponent(JqlQueryParser.class);
        // authorname 은 사용자 키다(JIRAUSER10000). Users 가 풀고 기억한다.
        Users users = new Users();

        for (GenericValue filter : delegator.findAll("SearchRequest")) {
            Long id = filter.getLong("id");
            String name = filter.getString("name");
            String author = users.displayName(filter.getString("author"));
            String jql = filter.getString("request");
            String label = (name == null ? "?" : name) + " (" + id + ")";

            if (jql == null || jql.trim().isEmpty()) {
                continue;
            }

            Set<FieldUsage> referenced;
            try {
                referenced = parseReferences(context, parser, jql);
            } catch (Exception e) {
                // JqlParseException 포함. 실패를 노출하고 최선 탐색으로 넘어간다.
                context.addProblem("filter", label, "janitor.fields.problem.filterJql",
                        e.getClass().getSimpleName());
                referenced = scanRaw(context, jql);
            }

            for (FieldUsage field : referenced) {
                context.addReference(field, new Reference(
                        ReferenceType.FILTER,
                        name,
                        String.valueOf(id),
                        (author == null ? "?" : author) + " · " + excerpt(jql),
                        AdminUrls.filter(id)));
            }
        }
    }

    private Set<FieldUsage> parseReferences(ScanContext context, JqlQueryParser parser, String jql)
            throws Exception {
        Set<FieldUsage> found = new LinkedHashSet<FieldUsage>();
        Query query = parser.parseQuery(jql);

        collectClause(context, query.getWhereClause(), found, 0);

        if (query.getOrderByClause() != null) {
            for (SearchSort sort : query.getOrderByClause().getSearchSorts()) {
                addByJqlName(context, sort.getField(), found);
            }
        }
        return found;
    }

    /** 절 트리를 재귀 순회한다. AND/OR/NOT로 얼마든지 중첩될 수 있다. */
    private void collectClause(ScanContext context, Clause clause, Set<FieldUsage> found, int depth) {
        if (clause == null || depth > 100) {
            return;
        }
        if (clause instanceof TerminalClause) {
            addByJqlName(context, clause.getName(), found);
        }
        List<Clause> children = clause.getClauses();
        if (children != null) {
            for (Clause child : children) {
                collectClause(context, child, found, depth + 1);
            }
        }
    }

    /**
     * JQL에 등장한 필드 표기 하나를 필드로 해석한다.
     * {@code cf[10001]} → ID, {@code customfield_10001} → ID, 그 외 → 이름.
     * 이름이 중복이면 후보 전체에 붙인다(어느 쪽인지 알 수 없으므로 빠뜨리지 않는 편을 택한다).
     */
    private void addByJqlName(ScanContext context, String jqlName, Set<FieldUsage> found) {
        if (jqlName == null || jqlName.trim().isEmpty()) {
            return;
        }
        String name = jqlName.trim();

        Matcher bracket = CF_BRACKET.matcher(name);
        if (bracket.matches()) {
            FieldUsage field = context.byNumericId(Long.parseLong(bracket.group(1)));
            if (field != null) {
                found.add(field);
            }
            return;
        }

        FieldUsage byId = context.byFieldId(name);
        if (byId != null) {
            found.add(byId);
            return;
        }

        found.addAll(context.byName(name));
    }

    /** 파싱이 안 될 때의 최선 탐색. 이름 매칭은 오탐이 크므로 ID 표기만 본다. */
    private Set<FieldUsage> scanRaw(ScanContext context, String jql) {
        Set<FieldUsage> found = new LinkedHashSet<FieldUsage>(context.findReferencedFields(jql));
        Matcher bracket = CF_BRACKET.matcher(jql);
        while (bracket.find()) {
            FieldUsage field = context.byNumericId(Long.parseLong(bracket.group(1)));
            if (field != null) {
                found.add(field);
            }
        }
        return found;
    }

    private static String excerpt(String jql) {
        String flat = jql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    @Override
    public boolean isEssential() {
        return false;
    }
}
