package net.ximatai.frp.server.service;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.WebSocketFrameType;
import io.vertx.core.net.NetSocket;
import net.ximatai.frp.server.config.Tunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TunnelLinker extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelLinker.class);
    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒心跳
    private static final int REQUEST_TIMEOUT = 30000; // 30秒请求超时

    // 协议常量
    private static final byte CONNECT = 0x01;
    private static final byte DATA = 0x02;
    private static final byte CLOSE = 0x03;

    private final Vertx vertx;
    private final Tunnel tunnel;

    // 存储客户端WebSocket连接 (clientId -> WebSocket)
    private final Map<String, ServerWebSocket> activeClients = new ConcurrentHashMap<>();

    // 存储用户请求上下文 (requestId -> RequestContext)
    private final Map<String, RequestContext> pendingRequests = new ConcurrentHashMap<>();

    public TunnelLinker(Vertx vertx, Tunnel tunnel) {
        this.vertx = vertx;
        this.tunnel = tunnel;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        int openPort = tunnel.openPort();
        int agentPort = tunnel.agentPort();

        LOGGER.info("Try To Link {}, OpenPort is {}, AgentPort is {}",
                tunnel.name(), openPort, agentPort);

        createAgentServer(agentPort)
                .compose(v -> createPublicServer(openPort))
                .onSuccess(v -> {
                    LOGGER.info("Link {} Success", tunnel.name());
                    startPromise.complete();
                })
                .onFailure(throwable -> {
                    LOGGER.error("Link {} Failed", tunnel.name(), throwable);
                    startPromise.fail(throwable);
                });
    }

    private Future<Void> createAgentServer(int port) {
        Promise<Void> promise = Promise.promise();

        HttpServerOptions options = new HttpServerOptions()
                .setRegisterWebSocketWriteHandlers(true);
        HttpServer server = vertx.createHttpServer(options);

        server
                .webSocketHandler(webSocket -> {
                    // 客户端连接处理
                    String clientId = UUID.randomUUID().toString();
                    LOGGER.info("FRP Client connected: {} @ {}", clientId, webSocket.remoteAddress());

                    // 将新客户端添加到活跃列表
                    activeClients.put(clientId, webSocket);

                    // 设置消息处理器
                    webSocket.frameHandler(frame -> handleClientFrame(clientId, frame));

                    // 设置关闭处理器
                    webSocket.closeHandler(v -> {
                        activeClients.remove(clientId);
                        LOGGER.warn("FRP Client disconnected: {}", clientId);
                    });

                    // 设置异常处理器
                    webSocket.exceptionHandler(ex -> {
                        LOGGER.error("WebSocket error for client {}", clientId, ex);
                        activeClients.remove(clientId);
                        webSocket.close();
                    });

                    // 启用心跳检测
                    setupHeartbeat(webSocket, clientId);
                })
                .invalidRequestHandler(request -> {
                    LOGGER.error("Invalid request: {}", request.uri());
                })
                .exceptionHandler(err -> {
                    LOGGER.error("Server error", err);
                })
                .listen(port)
                .onSuccess(s -> {
                    LOGGER.info("Agent server listening on port {}", port);
                    promise.complete();
                })
                .onFailure(err -> {
                    LOGGER.error("Agent server failed to start on port {}", port, err);
                    promise.fail(err);
                });

        return promise.future();
    }

    private Future<Void> createPublicServer(int port) {
        Promise<Void> promise = Promise.promise();

        vertx.createNetServer()
                .connectHandler(userSocket -> {
                    // 生成唯一请求ID
                    String requestId = UUID.randomUUID().toString();

                    LOGGER.debug("New user request: {}", requestId);

                    // 保存请求上下文
                    RequestContext context = new RequestContext(requestId, userSocket);
                    pendingRequests.put(requestId, context);

                    // 设置用户连接超时
                    context.setTimeoutId(vertx.setTimer(REQUEST_TIMEOUT, tid -> {
                        if (pendingRequests.remove(requestId) != null) {
                            userSocket.close();
                            LOGGER.warn("Request timed out: {}", requestId);
                        }
                    }));

                    // 将请求转发给代理客户端
                    if (!forwardRequestToClient(requestId, userSocket)) {
                        pendingRequests.remove(requestId);
                        userSocket.close();
                        LOGGER.error("No available clients for request: {}", requestId);
                    }

                    // 处理用户数据
                    userSocket.handler(data -> handleUserData(requestId, data));

                    // 处理用户关闭
                    userSocket.closeHandler(v -> {
                        RequestContext ctx = pendingRequests.remove(requestId);
                        if (ctx != null) {
                            vertx.cancelTimer(ctx.getTimeoutId());
                            sendCloseSignalToClient(requestId);
                        }
                    });

                    // 处理用户异常
                    userSocket.exceptionHandler(ex -> {
                        RequestContext ctx = pendingRequests.remove(requestId);
                        if (ctx != null) {
                            vertx.cancelTimer(ctx.getTimeoutId());
                            sendCloseSignalToClient(requestId);
                            LOGGER.error("User connection exception: {}", requestId, ex);
                        }
                    });
                })
                .listen(port)
                .onSuccess(server -> {
                    LOGGER.info("Public server listening on port {}", port);
                    promise.complete();
                })
                .onFailure(throwable -> {
                    LOGGER.error("Public server failed to start on port {}", port, throwable);
                    promise.fail(throwable);
                });

        return promise.future();
    }

    private void handleClientFrame(String clientId, WebSocketFrame frame) {
        if (frame.isClose()) {
            activeClients.remove(clientId);
            return;
        }

        if (frame.isPing()) {
            LOGGER.trace("Received PING from client {}", clientId);
            ServerWebSocket ws = activeClients.get(clientId);
            if (ws != null) {
                ws.writePong(Buffer.buffer("pong"));
            }
            return;
        }

        if (frame.type().equals(WebSocketFrameType.PONG)) {
            LOGGER.trace("Received PONG from client {}", clientId);
            return;
        }

        if (!frame.isBinary()) return;

        try {
            Buffer data = frame.binaryData();

            // 检查最小长度（至少包含操作码+UUID长度）
            if (data.length() < 17) {
                LOGGER.error("Invalid frame length from client {}: {}", clientId, data.length());
                return;
            }

            // 解析操作码
            byte opCode = data.getByte(0);

            // 解析请求ID（16字节的UUID）
            Buffer requestIdBuffer = data.getBuffer(1, 17);
            String requestId = new UUID(
                    requestIdBuffer.getLong(0),
                    requestIdBuffer.getLong(8)
            ).toString();

            // 解析有效载荷（如果存在）
            Buffer payload = data.length() > 17 ? data.getBuffer(17, data.length()) : null;

            RequestContext context = pendingRequests.get(requestId);
            if (context == null || context.isClosed()) {
                LOGGER.warn("Request context not found or closed: {}", requestId);
                return;
            }

            switch (opCode) {
                case DATA:
                    if (payload != null) {
                        context.getSocket().write(payload);
                        LOGGER.debug("Forwarded {} bytes to user for request {}", payload.length(), requestId);
                    }
                    break;

                case CLOSE:
                    RequestContext ctx = pendingRequests.remove(requestId);
                    if (ctx != null) {
                        vertx.cancelTimer(ctx.getTimeoutId());
                        ctx.getSocket().close();
                        LOGGER.debug("Closed user connection: {}", requestId);
                    }
                    break;

                default:
                    LOGGER.warn("Unknown op code {} from client {}", opCode, clientId);
            }
        } catch (Exception ex) {
            LOGGER.error("Error processing client frame", ex);
        }
    }

    private void handleUserData(String requestId, Buffer data) {
        // 1. 查找对应的请求上下文
        RequestContext context = pendingRequests.get(requestId);
        if (context == null || context.isClosed()) {
            return;
        }

        // 2. 如果客户端不可用，关闭用户连接
        if (activeClients.isEmpty()) {
            RequestContext ctx = pendingRequests.remove(requestId);
            if (ctx != null) {
                vertx.cancelTimer(ctx.getTimeoutId());
                ctx.getSocket().close();
                LOGGER.error("No active clients while forwarding data for {}", requestId);
            }
            return;
        }

        // 3. 构造数据帧：操作码(1字节) + 请求ID(16字节) + 数据
        Buffer frameData = Buffer.buffer(1 + 16 + data.length());

        // 添加操作码
        frameData.appendByte(DATA);

        // 添加请求ID (16字节)
        UUID uuid = UUID.fromString(requestId);
        frameData.appendLong(uuid.getMostSignificantBits());
        frameData.appendLong(uuid.getLeastSignificantBits());

        // 添加实际数据
        frameData.appendBuffer(data);

        // 4. 发送给所有可用客户端
        activeClients.forEach((clientId, client) -> {
            if (!client.isClosed()) {
                try {
                    client.writeBinaryMessage(frameData);
                    LOGGER.trace("Forwarded {} bytes to client {} for request {}",
                            data.length(), clientId, requestId);
                } catch (Exception ex) {
                    LOGGER.error("Failed to forward data to client {}", clientId, ex);
                }
            }
        });
    }

    private boolean forwardRequestToClient(String requestId, NetSocket userSocket) {
        if (activeClients.isEmpty()) {
            return false;
        }

        // 1. 选择第一个可用客户端
        ServerWebSocket client = activeClients.values().iterator().next();

        if (client.isClosed()) {
            return false;
        }

        try {
            // 2. 构造连接帧：操作码(1字节) + 请求ID(16字节)
            Buffer frame = Buffer.buffer(17);

            // 添加操作码
            frame.appendByte(CONNECT);

            // 添加请求ID (16字节)
            UUID uuid = UUID.fromString(requestId);
            frame.appendLong(uuid.getMostSignificantBits());
            frame.appendLong(uuid.getLeastSignificantBits());

            // 3. 发送给客户端
            client.writeBinaryMessage(frame);
            return true;
        } catch (Exception ex) {
            LOGGER.error("Failed to notify client about new request", ex);
            return false;
        }
    }

    private void sendCloseSignalToClient(String requestId) {
        // 1. 构造关闭帧：操作码(1字节) + 请求ID(16字节)
        Buffer frame = Buffer.buffer(17);

        // 添加操作码
        frame.appendByte(CLOSE);

        // 添加请求ID (16字节)
        UUID uuid = UUID.fromString(requestId);
        frame.appendLong(uuid.getMostSignificantBits());
        frame.appendLong(uuid.getLeastSignificantBits());

        // 2. 发送给所有客户端
        activeClients.forEach((clientId, client) -> {
            if (!client.isClosed()) {
                try {
                    client.writeBinaryMessage(frame);
                    LOGGER.debug("Sent CLOSE signal to client {} for request {}", clientId, requestId);
                } catch (Exception ex) {
                    LOGGER.error("Failed to send close signal to client {}", clientId, ex);
                }
            }
        });
    }

    private void setupHeartbeat(ServerWebSocket webSocket, String clientId) {
        // 1. 设置心跳发送定时器
        long timerId = vertx.setPeriodic(HEARTBEAT_INTERVAL, id -> {
            if (webSocket.isClosed()) {
                vertx.cancelTimer(id);
                return;
            }

            try {
                // 发送PING帧
                webSocket.writePing(Buffer.buffer("ping-" + System.currentTimeMillis()));
                LOGGER.trace("Sent PING to client {}", clientId);
            } catch (Exception ex) {
                LOGGER.error("Heartbeat failed for client {}", clientId, ex);
                vertx.cancelTimer(id);

                // 清理资源
                activeClients.remove(clientId);
                try {
                    webSocket.close();
                } catch (Exception ignore) {
                }
            }
        });

        // 2. 连接关闭时取消心跳
        webSocket.closeHandler(v -> vertx.cancelTimer(timerId));
    }

    // 请求上下文类，用于跟踪连接状态
    private static class RequestContext {
        private final String requestId;
        private final NetSocket socket;
        private long timeoutId;
        private boolean closed = false;

        public RequestContext(String requestId, NetSocket socket) {
            this.requestId = requestId;
            this.socket = socket;

            // 监听关闭事件
            socket.closeHandler(v -> closed = true);
        }

        public String getRequestId() {
            return requestId;
        }

        public NetSocket getSocket() {
            return socket;
        }

        public long getTimeoutId() {
            return timeoutId;
        }

        public void setTimeoutId(long timeoutId) {
            this.timeoutId = timeoutId;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
