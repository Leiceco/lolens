package com.lolens.services.lcu;

import com.lolens.kernel.event.ConnectionState;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/**
 * LCU 客户端：连接发现、生命周期维护、请求封装、WebSocket 订阅。
 *
 * <p>合规边界：仅读取 LCU 本地 API（不读写游戏内存）。</p>
 */
public interface LcuClient {

    ConnectionState state();

    /** 解析 lockfile 并连接。 */
    void connect();

    void disconnect();

    <T> T get(String endpoint, Class<T> type);

    <T> T post(String endpoint, Object body, Class<T> type);

    void put(String endpoint, Object body);

    /** PATCH 请求（选人阶段修改 action 等场景）。 */
    <T> T patch(String endpoint, Object body, Class<T> type);

    /** WebSocket 事件订阅（自动重连）。 */
    void subscribe(String eventUri, Consumer<JsonNode> handler);

    /** 连接状态变化监听。 */
    void onStateChanged(Consumer<ConnectionState> listener);
}
