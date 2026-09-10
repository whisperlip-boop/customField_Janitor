package com.bskim.jira.janitor.fields.store;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 일반·심층 스캔의 상호 배제. 전에는 "상대 플래그 확인 → 자기 플래그 CAS" 로 두 원자
 * 변수를 썼고, 두 요청이 동시에 오면 둘 다 통과했다(리뷰 지적). 잠금이 하나면 획득
 * 자체가 배타적이다 — 그것을 여기서 고정한다.
 */
public class ScanLockTest {

    @Test
    public void 하나만_잡는다() {
        ScanLock lock = new ScanLock();
        assertTrue(lock.tryAcquire(ScanLock.SCAN));
        assertFalse(lock.tryAcquire(ScanLock.DEEP));
        assertFalse(lock.tryAcquire(ScanLock.SCAN));
        assertEquals(ScanLock.SCAN, lock.holder());
    }

    @Test
    public void 자기_것만_놓는다() {
        ScanLock lock = new ScanLock();
        lock.tryAcquire(ScanLock.DEEP);
        lock.release(ScanLock.SCAN);
        assertTrue(lock.isHeldBy(ScanLock.DEEP));
        lock.release(ScanLock.DEEP);
        assertNull(lock.holder());
        assertTrue(lock.tryAcquire(ScanLock.SCAN));
    }

    @Test
    public void 동시에_들어와도_하나만_성공한다() throws Exception {
        final ScanLock lock = new ScanLock();
        final int threads = 32;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger acquired = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final String kind = i % 2 == 0 ? ScanLock.SCAN : ScanLock.DEEP;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        if (lock.tryAcquire(kind)) {
                            acquired.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        // 테스트 스레드
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        go.countDown();
        done.await();
        assertEquals(1, acquired.get());
    }

    @Test
    public void 버전을_모르면_스냅샷을_쓰지_않는다() {
        assertFalse(PluginVersion.isKnown(PluginVersion.UNKNOWN));
        assertFalse(PluginVersion.isKnown(null));
        assertTrue(PluginVersion.isKnown("1.3.2"));
    }
}
