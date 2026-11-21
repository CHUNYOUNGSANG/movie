package io.github.chunyoungsang.moviebot.support;

import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 JDBC 실행 횟수 카운터.
 * DataSource 를 프록시로 감싸 Statement 의 execute* / executeBatch 호출(= DB 왕복)을 센다.
 * 스케줄러 등 다른 스레드의 쿼리는 제외하고, start() 를 호출한 스레드의 쿼리만 센다.
 *
 * 사용: @Import(QueryCounter.Config.class) 후 QueryCounter.start() → 작업 → QueryCounter.count()
 */
public final class QueryCounter {

    private static final Set<String> EXECUTE_METHODS =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");

    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile Thread owner;

    private QueryCounter() {
    }

    public static void start() {
        COUNT.set(0);
        owner = Thread.currentThread();
    }

    public static int count() {
        return COUNT.get();
    }

    private static Object proxy(Object target, Class<?> type) {
        InvocationHandler handler = (p, method, args) -> {
            if (EXECUTE_METHODS.contains(method.getName()) && Thread.currentThread() == owner) {
                COUNT.incrementAndGet();
            }
            try {
                Object result = method.invoke(target, args);
                if (result instanceof Statement && method.getReturnType().isInterface()) {
                    return proxy(result, method.getReturnType());
                }
                if (result instanceof Connection) {
                    return proxy(result, Connection.class);
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        };
        return Proxy.newProxyInstance(QueryCounter.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static class Config {
        @org.springframework.context.annotation.Bean
        static BeanPostProcessor queryCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ds ? proxy(ds, DataSource.class) : bean;
                }
            };
        }
    }
}
