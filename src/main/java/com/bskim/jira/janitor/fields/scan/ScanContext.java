package com.bskim.jira.janitor.fields.scan;

import com.bskim.jira.janitor.fields.model.FieldUsage;
import com.bskim.jira.janitor.fields.model.Reference;
import com.bskim.jira.janitor.fields.model.ScanProblem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스캔 한 번이 공유하는 작업판. 수집기들이 여기에 참조를 쌓는다.
 *
 * <p>기획서 결정 3의 핵심: 필드마다 화면/워크플로를 반복 순회하지 않는다.
 * 전체 설정을 한 번 훑으면서 {@code fieldId → 참조 목록} 인덱스를 채운다.
 * 그래서 수집기는 "필드를 받아 찾는" 모양이 아니라 "설정을 훑으며 해당 필드에
 * 참조를 붙이는" 모양이다.
 *
 * <p>모든 조회는 숫자 ID 기준이다(기획서 결정 2). 이름 조회는 JQL처럼 이름밖에
 * 없는 경우에만 쓰고, 중복 이름은 후보 전체에 붙인다.
 */
public final class ScanContext {

    /** {@code customfield_10001} 또는 문자열 안에 박힌 같은 토큰을 잡는다. */
    private static final Pattern FIELD_ID_PATTERN = Pattern.compile("customfield_(\\d+)");

    private final Map<Long, FieldUsage> byNumericId = new HashMap<Long, FieldUsage>();
    private final Map<String, List<FieldUsage>> byLowerName = new HashMap<String, List<FieldUsage>>();
    private final List<ScanProblem> problems = new ArrayList<ScanProblem>();

    public ScanContext(Collection<FieldUsage> fields) {
        for (FieldUsage field : fields) {
            byNumericId.put(field.getNumericId(), field);
            String key = normalize(field.getName());
            List<FieldUsage> sameName = byLowerName.get(key);
            if (sameName == null) {
                sameName = new ArrayList<FieldUsage>();
                byLowerName.put(key, sameName);
            }
            sameName.add(field);
        }
        // 이름 중복은 정리 대상 인스턴스의 정상 상태다. 표시에 경고를 달아준다.
        for (List<FieldUsage> sameName : byLowerName.values()) {
            if (sameName.size() > 1) {
                for (FieldUsage field : sameName) {
                    field.setDuplicateName(true);
                }
            }
        }
    }

    public Collection<FieldUsage> getFields() {
        return byNumericId.values();
    }

    public FieldUsage byNumericId(long numericId) {
        return byNumericId.get(numericId);
    }

    /** {@code customfield_10001} 형태의 문자열로 찾는다. 형식이 아니면 null. */
    public FieldUsage byFieldId(String fieldId) {
        if (fieldId == null) {
            return null;
        }
        Matcher matcher = FIELD_ID_PATTERN.matcher(fieldId.trim());
        if (!matcher.matches()) {
            return null;
        }
        return byNumericId(Long.parseLong(matcher.group(1)));
    }

    /**
     * 이름으로 찾는다. 같은 이름이 여러 개면 전부 돌려준다 — 어느 쪽인지 알 수 없으니
     * 후보 전체에 참조를 붙이는 것이 안전하다(빠뜨려서 잘못 지우게 하는 쪽이 더 나쁘다).
     */
    public List<FieldUsage> byName(String name) {
        List<FieldUsage> hits = byLowerName.get(normalize(name));
        return hits == null ? Collections.<FieldUsage>emptyList() : hits;
    }

    /**
     * 임의의 문자열에서 {@code customfield_<id>} 형태로 참조되는 필드를 뽑아낸다.
     * 기획서 결정 5의 문자열 스캔이 여기 있다.
     *
     * <p>기본값이 "customfield_ 형태만"인 이유는 실측 때문이다. 기획서 5.3(5)는
     * 숫자 ID 단독 일치도 잡으라고 했는데, 실제로 돌려보니 Jira가 기본 생성하는
     * 워크플로에서 이런 것이 걸렸다:
     *
     * <pre>
     *   &lt;arg name="field.name"&gt;resolution&lt;/arg&gt;
     *   &lt;arg name="field.value"&gt;10000&lt;/arg&gt;   &lt;-- 해결책 ID 10000
     * </pre>
     *
     * 커스텀 필드 ID와 해결책·상태·화면·스텝 ID가 같은 10000번대 공간을 쓰기 때문에
     * 숫자 단독 매칭은 구조적으로 충돌한다. 그대로 두면 Jira 기본 필드가 전부
     * [위험]으로 찍히고, 그러면 3장의 상태 라벨이 의미를 잃는다.
     *
     * <p>그래서 숫자 단독 매칭은 {@link #findReferencedFieldsIncludingBareIds}로
     * 분리하고, 호출부가 그 결과를 "추정"으로 다루게 했다.
     */
    public Set<FieldUsage> findReferencedFields(String haystack) {
        return scan(haystack, false);
    }

    /**
     * {@code customfield_<id>}뿐 아니라 <b>숫자 ID 단독</b>도 잡는다.
     *
     * <p>서드파티 앱(JSU/JMWE/ScriptRunner)이 arg 값에 숫자 ID만 넣는 경우가 있어서
     * 필요하다. 다만 오탐이 섞이므로 호출부는 이 결과를 확정 참조로 취급하면 안 된다
     * ({@link #findReferencedFields} 주석의 근거 참고).
     */
    public Set<FieldUsage> findReferencedFieldsIncludingBareIds(String haystack) {
        return scan(haystack, true);
    }

    private Set<FieldUsage> scan(String haystack, boolean includeBareIds) {
        if (haystack == null || haystack.isEmpty()) {
            return Collections.emptySet();
        }
        Set<FieldUsage> hits = new LinkedHashSet<FieldUsage>();

        Matcher matcher = FIELD_ID_PATTERN.matcher(haystack);
        Set<Integer> consumed = new HashSet<Integer>();
        while (matcher.find()) {
            FieldUsage field = byNumericId(Long.parseLong(matcher.group(1)));
            if (field != null) {
                hits.add(field);
            }
            // customfield_10001 안의 "10001"을 숫자 단독으로 또 세지 않게 위치를 기록한다.
            consumed.add(matcher.start(1));
        }

        if (!includeBareIds) {
            return hits;
        }

        Matcher numbers = Pattern.compile("\\d{3,}").matcher(haystack);
        while (numbers.find()) {
            if (consumed.contains(numbers.start())) {
                continue;
            }
            FieldUsage field = byNumericId(Long.parseLong(numbers.group()));
            if (field != null) {
                hits.add(field);
            }
        }
        return hits;
    }

    public void addReference(FieldUsage field, Reference reference) {
        if (field != null) {
            field.addReference(reference);
        }
    }

    public void addReference(Collection<FieldUsage> fields, Reference reference) {
        for (FieldUsage field : fields) {
            field.addReference(reference);
        }
    }

    /** 확인하지 못한 항목을 기록한다. 조용히 넘기지 않는다(함정 7). */
    public void addProblem(String area, String target, Throwable cause) {
        String message = cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        problems.add(new ScanProblem(area, target, message));
    }

    public void addProblem(String area, String target, String message) {
        problems.add(new ScanProblem(area, target, message));
    }

    public List<ScanProblem> getProblems() {
        return problems;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ENGLISH);
    }
}
