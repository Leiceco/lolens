package com.lolens.kernel.event;

import java.util.function.Consumer;

/**
 * 事件总线：模块间唯一的解耦通信方式。
 *
 * <p>核心事件定义在内核（{@link GameEvent}），模块可定义自己的事件并发布/订阅。</p>
 */
public interface EventBus {

    /**
     * 订阅某类事件。
     *
     * @param eventType 事件类型
     * @param handler   处理器
     * @param <T>       事件类型参数
     * @return 订阅句柄，可用于退订
     */
    <T extends GameEvent> Subscription subscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 发布事件，通知所有订阅者。
     *
     * @param event 事件实例
     */
    void publish(GameEvent event);

    /** 订阅句柄。 */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
