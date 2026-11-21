package io.github.chunyoungsang.moviebot.infra.cgv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PerThreadResource")
class PerThreadResourceTest {

    /** 만든 스레드가 아닌 곳에서 쓰이면 기록하는 가짜 리소스 (Playwright 의 스레드 제약 흉내) */
    static class FakeSession implements AutoCloseable {
        final Thread owner = Thread.currentThread();
        boolean closed;
        static final AtomicInteger foreignUse = new AtomicInteger();

        void use() {
            if (Thread.currentThread() != owner) foreignUse.incrementAndGet();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    @DisplayName("스레드마다 자기 인스턴스를 만들고, 다른 스레드의 인스턴스를 쓰지 않는다")
    void eachThreadGetsItsOwnInstance() throws Exception {
        PerThreadResource<FakeSession> resource = new PerThreadResource<>(FakeSession::new);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Set<FakeSession> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch allStarted = new CountDownLatch(3);
        FakeSession.foreignUse.set(0);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            futures.add(pool.submit(() -> {
                FakeSession session = resource.get();
                session.use();
                seen.add(session);
                allStarted.countDown();
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(seen).as("스레드 3개 → 인스턴스 3개 (작업 30개여도 재사용)").hasSize(3);
        assertThat(resource.size()).isEqualTo(3);
        assertThat(FakeSession.foreignUse.get()).as("만든 스레드가 아닌 곳에서 사용된 횟수").isZero();
    }

    @Test
    @DisplayName("같은 스레드에서는 같은 인스턴스를 돌려준다")
    void sameThreadReusesInstance() {
        PerThreadResource<FakeSession> resource = new PerThreadResource<>(FakeSession::new);
        assertThat(resource.get()).isSameAs(resource.get());
    }

    @Test
    @DisplayName("discardCurrent 하면 닫고, 다음 get 에서 새로 만든다")
    void discardCurrentClosesAndRecreates() {
        PerThreadResource<FakeSession> resource = new PerThreadResource<>(FakeSession::new);
        FakeSession first = resource.get();

        resource.discardCurrent();

        assertThat(first.closed).isTrue();
        assertThat(resource.get()).isNotSameAs(first);
        assertThat(resource.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("closeAll 은 만들어진 모든 인스턴스를 닫는다")
    void closeAllClosesEveryInstance() throws Exception {
        PerThreadResource<FakeSession> resource = new PerThreadResource<>(FakeSession::new);
        FakeSession mine = resource.get();
        FakeSession other = Executors.newSingleThreadExecutor().submit(resource::get).get();

        resource.closeAll();

        assertThat(mine.closed).isTrue();
        assertThat(other.closed).isTrue();
        assertThat(resource.size()).isZero();
    }

    @Test
    @DisplayName("생성에 실패하면 캐시하지 않고 다음 호출에서 다시 시도한다")
    void failedCreationIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        PerThreadResource<FakeSession> resource = new PerThreadResource<>(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("브라우저 실행 실패");
            return new FakeSession();
        });

        assertThatThrownBy(resource::get).isInstanceOf(IllegalStateException.class);
        assertThat(resource.get()).isNotNull();
        assertThat(attempts.get()).isEqualTo(2);
    }
}
