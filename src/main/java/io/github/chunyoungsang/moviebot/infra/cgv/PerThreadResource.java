package io.github.chunyoungsang.moviebot.infra.cgv;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 스레드마다 자기 전용 인스턴스를 하나씩 만들어 재사용한다.
 *
 * Playwright 는 스레드 안전하지 않아 객체를 만든 스레드에서만 사용해야 한다. 인스턴스를 하나 공유하면
 * 호출 전체를 직렬화(synchronized)해야 하므로, 스레드마다 따로 두어 동시에 쓸 수 있게 한다.
 * 크롤링 스레드풀의 스레드는 계속 살아 있으므로 인스턴스는 처음 한 번만 만들어진다.
 */
@Slf4j
public class PerThreadResource<T extends AutoCloseable> {

    private final Supplier<T> factory;
    private final ThreadLocal<T> perThread = new ThreadLocal<>();
    private final Set<T> all = ConcurrentHashMap.newKeySet();

    public PerThreadResource(Supplier<T> factory) {
        this.factory = factory;
    }

    /** 현재 스레드의 인스턴스. 없으면 만든다 (생성 실패 시 예외를 그대로 던지고, 다음 호출에서 다시 시도) */
    public T get() {
        T resource = perThread.get();
        if (resource == null) {
            resource = factory.get();
            perThread.set(resource);
            all.add(resource);
        }
        return resource;
    }

    /** 현재 스레드의 인스턴스를 닫고 버린다 (다음 get() 에서 새로 만든다) */
    public void discardCurrent() {
        T resource = perThread.get();
        if (resource == null) return;
        perThread.remove();
        all.remove(resource);
        closeQuietly(resource);
    }

    /** 만들어진 모든 인스턴스를 닫는다 (애플리케이션 종료 시) */
    public void closeAll() {
        for (T resource : all) {
            closeQuietly(resource);
        }
        all.clear();
    }

    public int size() {
        return all.size();
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("리소스 정리 실패: {}", e.getMessage());
        }
    }
}
