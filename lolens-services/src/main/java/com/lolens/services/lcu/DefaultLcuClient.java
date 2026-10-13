package com.lolens.services.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lolens.kernel.event.ConnectionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LCU 客户端默认实现：
 * <ul>
 *   <li>lockfile 轮询（2~3s）+ 端口变化检测 + 自动重连</li>
 *   <li>自定义 SSLContext 信任自签名证书</li>
 *   <li>WebSocket 订阅（HTTP 请求封装；WebSocket 可后续扩展）</li>
 *   <li>所有请求带超时与异常兜底</li>
 * </ul>
 */
public class DefaultLcuClient implements LcuClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultLcuClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_LOCKFILE =
            Path.of("C:/Riot Games/League of Legends/lockfile");
    /** 进程命令行探测的最小间隔（避免 lockfile 缺失时每 2s 拉起子进程）。 */
    private static final long PROCESS_PROBE_INTERVAL_MS = 5000;

    private final Path lockfilePath;
    /** 连接调度器：disconnect 时关闭并置空，connect 时惰性重建，支持断开后重连。 */
    private ScheduledExecutorService scheduler;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final List<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Lockfile> currentLockfile = new AtomicReference<>();

    private volatile HttpClient httpClient;
    private volatile long lastProcessProbeMs = 0;

    public DefaultLcuClient() {
        this(DEFAULT_LOCKFILE);
    }

    public DefaultLcuClient(Path lockfilePath) {
        this.lockfilePath = lockfilePath;
    }

    @Override
    public synchronized void connect() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return; // 已连接，忽略重复 connect
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lcu-connector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tryConnect, 0, 2, TimeUnit.SECONDS);
    }

    private void tryConnect() {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        ResolveResult result = resolveConnection();
        if (result.lockfile == null) {
            // 限频跳过轮：保持现状，避免已连接状态被抖动为未连接；
            // 仅当真正探测失败（客户端确实不在）时才标记断开。
            if (!result.skipped) {
                setState(ConnectionState.DISCONNECTED);
            }
            return;
        }
        Lockfile lockfile = result.lockfile;
        Lockfile prev = currentLockfile.get();
        // 会话判定：auth token 每次客户端启动都会重新生成，端口+token 相同视为同一会话
        boolean sameSession = prev != null && prev.port() == lockfile.port()
                && prev.password().equals(lockfile.password());
        if (sameSession) {
            // 端口未变：以健康检查结果决定状态，防止 lockfile 残留（客户端已崩溃）导致虚假连接；
            // 不重建 client（重建无法解决会话失效问题），待客户端重启产生新 lockfile 后再重建
            if (ping()) {
                setState(ConnectionState.CONNECTED);
            } else {
                setState(ConnectionState.DISCONNECTED);
            }
            return;
        }
        currentLockfile.set(lockfile);
        this.httpClient = buildHttpClient(lockfile);
        setState(ConnectionState.CONNECTED);
        log.info("LCU 已连接: {} (pid={})", lockfile.baseUrl(), lockfile.pid());
    }

    /**
     * 健康检查：向 gameflow-phase 发一个轻量 GET。只要收到任何 HTTP 响应
     * （即使 404）即认为 LCU 服务存活；仅连接层异常视为失效。
     */
    private boolean ping() {
        HttpClient client = this.httpClient;
        if (client == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(currentLockfile.get().baseUrl()
                            + "/lol-gameflow/v1/gameflow-phase"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 连接解析结果：lockfile 非空表示解析成功；skipped=true 表示处于进程探测限频窗口内。 */
    private record ResolveResult(Lockfile lockfile, boolean skipped) {
        static ResolveResult skip() {
            return new ResolveResult(null, true);
        }
        static ResolveResult none() {
            return new ResolveResult(null, false);
        }
        static ResolveResult found(Lockfile lockfile) {
            return new ResolveResult(lockfile, false);
        }
    }

    private ResolveResult resolveConnection() {
        // 1) 优先读 lockfile（快路径，无子进程开销，适用于国际服默认路径或显式配置）。
        Optional<Lockfile> fromLockfile = LockfileParser.parse(lockfilePath);
        if (fromLockfile.isPresent()) {
            return ResolveResult.found(fromLockfile.get());
        }
        // 2) 回退：从 LeagueClientUx.exe 进程命令行解析 --app-port / --remoting-auth-token，
        //    覆盖 WeGame 国服与自定义安装路径。限频以避免每 2s 拉起子进程。
        long now = System.currentTimeMillis();
        if (now - lastProcessProbeMs < PROCESS_PROBE_INTERVAL_MS) {
            return ResolveResult.skip();
        }
        lastProcessProbeMs = now;
        return LcuProcessResolver.resolve()
                .map(ResolveResult::found)
                .orElseGet(ResolveResult::none);
    }

    private HttpClient buildHttpClient(Lockfile lockfile) {
        // java.net.http.HttpClient 无 hostnameVerifier 配置项；
        // 通过信任自签名证书的 SSLContext 完成 LCU 本地回环 TLS 握手。
        return HttpClient.newBuilder()
                .sslContext(SslTrustAllContexts.createTrustAllContext())
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private String basicAuth() {
        Lockfile lf = currentLockfile.get();
        if (lf == null) {
            return "";
        }
        String token = "riot:" + lf.password();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private URI endpoint(String endpoint) {
        Lockfile lf = currentLockfile.get();
        if (lf == null) {
            throw new IllegalStateException("LCU 未连接");
        }
        return URI.create(lf.baseUrl() + endpoint);
    }

    private HttpRequest.Builder baseRequest(String endpoint) {
        return HttpRequest.newBuilder(endpoint(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", basicAuth());
    }

    @Override
    public <T> T get(String endpoint, Class<T> type) {
        HttpRequest request = baseRequest(endpoint).GET().build();
        return send(request, type);
    }

    @Override
    public <T> T post(String endpoint, Object body, Class<T> type) {
        try {
            HttpRequest request = baseRequest(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body instanceof String s ? s : MAPPER.writeValueAsString(body)))
                    .build();
            return send(request, type);
        } catch (Exception e) {
            throw new LcuException("POST 请求失败: " + endpoint, e);
        }
    }

    @Override
    public void put(String endpoint, Object body) {
        try {
            HttpRequest request = baseRequest(endpoint)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            body instanceof String s ? s : MAPPER.writeValueAsString(body)))
                    .build();
            send(request, JsonNode.class);
        } catch (Exception e) {
            throw new LcuException("PUT 请求失败: " + endpoint, e);
        }
    }

    @Override
    public <T> T patch(String endpoint, Object body, Class<T> type) {
        try {
            HttpRequest request = baseRequest(endpoint)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                            body instanceof String s ? s : MAPPER.writeValueAsString(body)))
                    .build();
            return send(request, type);
        } catch (Exception e) {
            throw new LcuException("PATCH 请求失败: " + endpoint, e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T send(HttpRequest request, Class<T> type) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // 4xx/5xx 视为失败并显式抛异常，避免上层拿到错误 JSON 误判为正常数据
                throw new LcuException("LCU 请求失败: " + request.uri() + " -> " + response.statusCode(),
                        null);
            }
            String body = response.body();
            if (type == String.class) {
                return (T) body;
            }
            if (type == JsonNode.class || body == null || body.isBlank()) {
                return type == JsonNode.class ? (T) MAPPER.readTree(body == null ? "{}" : body) : null;
            }
            return MAPPER.readValue(body, type);
        } catch (LcuException e) {
            throw e;
        } catch (Exception e) {
            throw new LcuException("LCU 请求失败: " + request.uri(), e);
        }
    }

    @Override
    public void subscribe(String eventUri, Consumer<JsonNode> handler) {
        // v1.0 使用轮询降级：订阅的端点按周期 GET，由调用方通过 scheduler 驱动。
        log.debug("订阅端点（轮询模式）: {}", eventUri);
    }

    @Override
    public void onStateChanged(Consumer<ConnectionState> listener) {
        stateListeners.add(listener);
    }

    @Override
    public ConnectionState state() {
        return state.get();
    }

    @Override
    public synchronized void disconnect() {
        ScheduledExecutorService s = this.scheduler;
        if (s != null) {
            s.shutdownNow();
        }
        this.scheduler = null;
        setState(ConnectionState.DISCONNECTED);
    }

    private void setState(ConnectionState newState) {
        ConnectionState prev = state.getAndSet(newState);
        if (prev != newState) {
            stateListeners.forEach(l -> {
                try {
                    l.accept(newState);
                } catch (Exception e) {
                    log.warn("状态监听器异常: {}", e.getMessage());
                }
            });
        }
    }

    /** LCU 请求异常。 */
    public static class LcuException extends RuntimeException {
        public LcuException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
