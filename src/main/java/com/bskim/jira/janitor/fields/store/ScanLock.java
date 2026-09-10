package com.bskim.jira.janitor.fields.store;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 일반 스캔과 심층 스캔이 동시에 돌지 않게 하는 <b>하나의</b> 잠금.
 *
 * <p>전에는 서비스마다 {@code AtomicBoolean} 을 두고 "상대 isRunning() 확인 → 자기 CAS"
 * 순서로 막았다. 확인과 획득이 서로 다른 원자 변수라 두 요청이 동시에 오면 둘 다 상대를
 * false 로 보고 둘 다 성공한다(리뷰 지적 — 실측한 409 는 순차 요청이었다).
 * 잠금이 하나면 획득 자체가 배타적이다.
 */
public final class ScanLock {

    public static final String SCAN = "scan";
    public static final String DEEP = "deep";

    private static final ScanLock INSTANCE = new ScanLock();

    private final AtomicReference<String> holder = new AtomicReference<String>();

    ScanLock() {
    }

    public static ScanLock getInstance() {
        return INSTANCE;
    }

    /** @return 획득했으면 true. 누가 들고 있든(자기 자신 포함) 이미 잡혀 있으면 false. */
    public boolean tryAcquire(String kind) {
        return holder.compareAndSet(null, kind);
    }

    /** 자기 것일 때만 놓는다. 다른 쪽이 들고 있는 잠금을 실수로 풀지 않는다. */
    public void release(String kind) {
        holder.compareAndSet(kind, null);
    }

    /** 지금 들고 있는 쪽. 없으면 null. 409 응답이 "무엇 때문에" 막혔는지 알려주는 데 쓴다. */
    public String holder() {
        return holder.get();
    }

    public boolean isHeldBy(String kind) {
        return kind != null && kind.equals(holder.get());
    }
}
