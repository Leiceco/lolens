package com.lolens.kernel.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 异步事件总线实现：发布在独立线程池执行，避免阻塞游戏状态轮询线程。
 * 对 {@link GamePhaseChanged} 等需要 UI 立即刷新的轻量事件可改用同步发布。
 */
public class AsyncEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(AsyncEventBus.class);

    private final Map<Class<?>, Set<HandlerEntry<?>>> subscribers = new ConcurrentHashMap<>();
    // 固定线程池：避免高频事件下无上限创建线程，同时限制峰值并发
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "lolens-event");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("unchecked")
    @Override
    public <T extends GameEvent> Subscription subscribe(Class<T> eventType, Consumer<T> handler) {
        Set<HandlerEntry<?>> set = subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArraySet<>());
        HandlerEntry<T> entry = new HandlerEntry<>(eventType, handler);
        set.add(entry);
        return () -> set.remove(entry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void publish(GameEvent event) {
        Set<HandlerEntry<?>> handlers = subscribers.get(event.getClass());
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (HandlerEntry<?> entry : handlers) {
            executor.execute(() -> {
                try {
                    ((HandlerEntry) entry).handler.accept(event);
                } catch (Exception e) {
                    log.error("事件处理失败: {} -> {}", event.getClass().getSimpleName(), e.getMessage(), e);
                }
            });
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private record HandlerEntry<T extends GameEvent>(Class<T> type, Consumer<T> handler) {}
}
