package com.bskim.jira.janitor.fields.scan.collector;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.user.ApplicationUser;

import java.util.HashMap;
import java.util.Map;

/**
 * 사용자 <b>키</b>({@code JIRAUSER10000})를 표시 이름으로 푼다.
 *
 * <p>{@code searchrequest.authorname}, {@code columnlayout.username} 둘 다 이름처럼 보이는
 * 컬럼명에 키가 들어 있다(docs/00 함정·32번). 두 수집기가 같은 코드를 따로 갖고 있었고
 * 한쪽만 빈 문자열 가드가 있었다(리뷰 지적) — 한 곳으로 모은다.
 *
 * <p>인스턴스는 수집 한 번의 수명이다. 같은 키를 반복해 묻지 않게 기억한다 — 개인 컬럼
 * 설정은 사용자마다 컬럼 수만큼 행이 나와서, 2,000명 × 8컬럼이면 조회가 16,000번이었다.
 */
public final class Users {

    /** 키 → 표시 이름. 테스트가 바꿔 끼울 수 있게 열어 둔다. */
    public interface Resolver {
        /** @return 표시 이름. 모르면 null. */
        String displayName(String userKey);
    }

    static final Resolver JIRA = new Resolver() {
        @Override
        public String displayName(String userKey) {
            try {
                ApplicationUser user = ComponentAccessor.getUserManager().getUserByKey(userKey);
                return user == null ? null : user.getDisplayName();
            } catch (RuntimeException e) {
                // 사용자 조회 실패는 표시 문제일 뿐이다. 부르는 쪽이 키를 그대로 쓴다.
                return null;
            }
        }
    };

    private final Resolver resolver;
    private final Map<String, String> cache = new HashMap<String, String>();

    public Users() {
        this(JIRA);
    }

    Users(Resolver resolver) {
        this.resolver = resolver;
    }

    /** 표시 이름. 못 풀면 키를 그대로 준다 — 빈 칸보다는 키가 낫다. 빈 키는 null. */
    public String displayName(String userKey) {
        if (userKey == null || userKey.trim().isEmpty()) {
            return null;
        }
        String cached = cache.get(userKey);
        if (cached != null) {
            return cached;
        }
        String name = resolver.displayName(userKey);
        String shown = name == null || name.trim().isEmpty() ? userKey : name;
        cache.put(userKey, shown);
        return shown;
    }
}
