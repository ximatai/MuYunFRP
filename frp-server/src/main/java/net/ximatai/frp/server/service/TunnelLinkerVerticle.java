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
import net.ximatai.frp.common.MessageUtil;
import net.ximatai.frp.common.OperationType;
import net.ximatai.frp.server.config.Tunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.ximatai.frp.common.MessageUtil.OPERATION_WIDTH;

public class TunnelLinkerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelLinkerVerticle.class);

    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒心跳
    private static final int REQUEST_TIMEOUT = 60000 * 60; // 60分钟请求超时

    private static final int MAX_WEBSOCKET_FRAME_SIZE = 65536; //  WebSocket帧最大长度，默认即为该值，主要不要超过服务端的设置

    private final Vertx vertx;
    private final Tunnel tunnel;

    private ServerWebSocket activeClient;

    // 存储用户请求上下文 (requestId -> RequestContext)
    private final Map<String, RequestContext> pendingRequests = new ConcurrentHashMap<>();

    public TunnelLinkerVerticle(Vertx vertx, Tunnel tunnel) {
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
                .setRegisterWebSocketWriteHandlers(true)
                .setMaxWebSocketFrameSize(MAX_WEBSOCKET_FRAME_SIZE);

        HttpServer server = vertx.createHttpServer(options);

        server
                .webSocketHandler(webSocket -> {
                    // 客户端连接处理
                    String clientId = UUID.randomUUID().toString();
                    LOGGER.info("FRP Client connected: {} @ {}", clientId, webSocket.remoteAddress());

                    // 将新客户端添加到活跃列表
                    activeClient = webSocket;

                    // 设置消息处理器
                    webSocket.frameHandler(frame -> handleClientFrame(clientId, frame));

                    // 设置关闭处理器
                    webSocket.closeHandler(v -> {
                        activeClient = null;
                        LOGGER.warn("FRP Client disconnected: {}", clientId);
                    });

                    // 设置异常处理器
                    webSocket.exceptionHandler(ex -> {
                        LOGGER.error("WebSocket error for client {}", clientId, ex);
                        activeClient = null;
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
                    userSocket.handler(data -> {
                        while ((data.length() + OPERATION_WIDTH) > MAX_WEBSOCKET_FRAME_SIZE) {
                            handleUserData(requestId, data.slice(0, MAX_WEBSOCKET_FRAME_SIZE - OPERATION_WIDTH));
                            data = data.slice(MAX_WEBSOCKET_FRAME_SIZE - OPERATION_WIDTH, data.length());
                        }

                        handleUserData(requestId, data);
                    });

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
            activeClient = null;
            return;
        }

        if (frame.isPing()) {
            LOGGER.debug("Received PING from client {}", clientId);
            if (activeClient != null) {
                activeClient.writePong(Buffer.buffer("pong"));
            }
            return;
        }

        if (frame.type().equals(WebSocketFrameType.PONG)) {
            LOGGER.debug("Received PONG from client {}", clientId);
            return;
        }

        if (!frame.isBinary()) return;

        try {
            Buffer data = frame.binaryData();

            // 检查最小长度（至少包含操作码+UUID长度）
            if (data.length() < OPERATION_WIDTH) {
                LOGGER.error("Invalid frame length from client {}: {}", clientId, data.length());
                return;
            }

            OperationType operationType = MessageUtil.getOperationType(data);
            String requestId = MessageUtil.getRequestId(data);
            Buffer payload = MessageUtil.getPayload(data);

            RequestContext context = pendingRequests.get(requestId);
            if (context == null || context.isClosed()) {
                LOGGER.warn("Request context not found or closed: {}", requestId);
                return;
            }

            switch (operationType) {
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
                    LOGGER.warn("Unknown op code {} from client {}", operationType, clientId);
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

        if (!checkClientAlive(requestId)) {
            return;
        }

        try {
            activeClient.writeBinaryMessage(MessageUtil.buildDataMessage(requestId, data));
            LOGGER.debug("Forwarded {} bytes to client {} for request {}",
                    data.length(), activeClient.hashCode(), requestId);
        } catch (Exception ex) {
            LOGGER.error("Failed to forward data to client {}", activeClient.hashCode(), ex);
        }

    }

    private boolean forwardRequestToClient(String requestId, NetSocket userSocket) {
        if (!checkClientAlive(requestId)) {
            return false;
        }

        try {
            activeClient.writeBinaryMessage(MessageUtil.buildOperationMessage(requestId, OperationType.CONNECT));
            return true;
        } catch (Exception ex) {
            LOGGER.error("Failed to notify client about new request", ex);
            return false;
        }
    }

    private void sendCloseSignalToClient(String requestId) {

        if (checkClientAlive(requestId)) {
            try {
                activeClient.writeBinaryMessage(MessageUtil.buildOperationMessage(requestId, OperationType.CLOSE));
                LOGGER.debug("Sent CLOSE signal to client {} for request {}", activeClient.hashCode(), requestId);
            } catch (Exception ex) {
                LOGGER.error("Failed to send close signal to client {}", activeClient.hashCode(), ex);
            }
        }

    }

    private boolean checkClientAlive(String requestId) {
        if (activeClient == null) {
            RequestContext ctx = pendingRequests.remove(requestId);
            if (ctx != null) {
                vertx.cancelTimer(ctx.getTimeoutId());
                ctx.getSocket().close();
                LOGGER.error("No active clients while forwarding data for {}", requestId);
            }
            return false;
        }
        return !activeClient.isClosed();
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
                activeClient = null;
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
