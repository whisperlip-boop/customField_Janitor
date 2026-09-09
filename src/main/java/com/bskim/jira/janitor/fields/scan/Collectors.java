package com.bskim.jira.janitor.fields.scan;

import com.bskim.jira.janitor.fields.scan.collector.ContextCollector;
import com.bskim.jira.janitor.fields.scan.collector.FieldConfigCollector;
import com.bskim.jira.janitor.fields.scan.collector.FilterCollector;
import com.bskim.jira.janitor.fields.scan.collector.GadgetCollector;
import com.bskim.jira.janitor.fields.scan.collector.ManagedFieldCollector;
import com.bskim.jira.janitor.fields.scan.collector.SchemeCollector;
import com.bskim.jira.janitor.fields.scan.collector.ScreenCollector;
import com.bskim.jira.janitor.fields.scan.collector.WorkflowCollector;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 수집기 실행 순서. 기획서 9장의 진행률 단계 순서와 같아야 한다.
 */
public final class Collectors {

    private static final List<ReferenceCollector> ALL = Collections.unmodifiableList(
            Arrays.<ReferenceCollector>asList(
                    // 가장 먼저. 뒤쪽 수집기가 실패해도 "앱이 잠근 필드"라는 사실은 남아야 한다.
                    new ManagedFieldCollector(),
                    new ScreenCollector(),
                    new FieldConfigCollector(),
                    new ContextCollector(),
                    new WorkflowCollector(),
                    new FilterCollector(),
                    new SchemeCollector(),
                    new GadgetCollector()));

    private Collectors() {
    }

    public static List<ReferenceCollector> all() {
        return ALL;
    }
}
