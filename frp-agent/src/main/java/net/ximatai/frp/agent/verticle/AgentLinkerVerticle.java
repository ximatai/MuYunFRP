package net.ximatai.frp.agent.verticle;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.NetClient;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.common.MessageUtil;
import net.ximatai.frp.common.OperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static net.ximatai.frp.common.MessageUtil.OPERATION_WIDTH;

public class AgentLinkerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLinkerVerticle.class);

    private static final long HEARTBEAT_INTERVAL = 30000;      // 30秒心跳
    private static final long LIVE_CHECK_INTERVAL = 10000;      // 10秒保活检查

    private static final int MAX_WEBSOCKET_FRAME_SIZE = 65536; //  WebSocket帧最大长度，默认即为该值，主要不要超过服务端的设置
    private static final int PROTOCOL_VERSION = 1;
    /** Per-request cap while the proxy TCP connection is still being established or is back-pressured. */
    private static final int MAX_PENDING_REQUEST_BYTES = 1024 * 1024;

    private final Agent agent;
    private WebSocket controlSocket;
    private WebSocketClient controlClient;
    private NetClient targetClient;
    private boolean authenticated;
    private boolean stopped;
    private long liveCheckTimerId = -1;
    private long heartbeatTimerId = -1;

    // 请求在收到 CONNECT 时注册，保证后续 DATA 可以按到达顺序进入同一个队列。
    private final Map<String, RequestConnection> pendingRequests = new HashMap<>();

    public AgentLinkerVerticle(Agent agent) {
        this.agent = agent;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        stopped = false;
        targetClient = vertx.createNetClient();
        controlClient = vertx.createWebSocketClient(options(agent.frpTunnel()));
        connectToFrpTunnel()
                .onComplete(startPromise);

        liveCheckTimerId = vertx.setPeriodic(LIVE_CHECK_INTERVAL, timerId -> {
            if (this.controlSocket == null) {
                connectToFrpTunnel();
            }
        });

        heartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL, id -> {
            try {
                if (controlSocket == null) return;

                controlSocket.writePing(Buffer.buffer("ping-" + System.currentTimeMillis()));
                LOGGER.trace("Sent heartbeat PING to server");
            } catch (Exception ex) {
                LOGGER.error("Failed to send heartbeat", ex);
                handleConnectionLoss(controlSocket);
            }
        });
    }

    private void setControlSocket(WebSocket controlSocket) {
        if (this.controlSocket != null && this.controlSocket != controlSocket) {
            closeRequestsForControlSocket(this.controlSocket);
            if (!this.controlSocket.isClosed()) {
                this.controlSocket.close();
            }
        }
        this.controlSocket = controlSocket;
        this.authenticated = false;
    }

    private Future<Void> connectToFrpTunnel() {
        if (stopped) {
            return Future.failedFuture("Agent linker is stopped");
        }
        Promise<Void> promise = Promise.promise();

        FrpTunnel frpTunnel = agent.frpTunnel();
        WebSocketClient client = controlClient;
        if (client == null) {
            return Future.failedFuture("FRP control client is unavailable");
        }

        LOGGER.info("Connecting to FRP tunnel at {}:{}", frpTunnel.host(), frpTunnel.port());

        vertx.sharedData().getLock("frp-agent-lock")
                .onSuccess(lock -> {
                    if (stopped) {
                        lock.release();
                        promise.fail("Agent linker is stopped");
                        return;
                    }
                    client
                            .connect("/")
                            .onSuccess(ws -> {
                                if (stopped) {
                                    ws.close();
                                    promise.fail("Agent linker stopped while connecting");
                                    lock.release();
                                    return;
                                }
                                LOGGER.info("Successfully connected to FRP server");
                                final boolean[] completed = {false};
                                final long[] authTimerId = new long[1];

                                // 保存控制通道socket
                                setControlSocket(ws);

                                // 设置关闭处理器
                                ws.closeHandler(v -> {
                                    LOGGER.warn("Connection to FRP server closed");
                                    if (!completed[0]) {
                                        completed[0] = true;
                                        vertx.cancelTimer(authTimerId[0]);
                                        promise.fail("FRP connection closed before auth");
                                    }
                                    handleConnectionLoss(ws);
                                });

                                // 设置异常处理器
                                ws.exceptionHandler(ex -> {
                                    LOGGER.error("WebSocket connection error", ex);
                                    if (!completed[0]) {
                                        completed[0] = true;
                                        vertx.cancelTimer(authTimerId[0]);
                                        promise.fail(ex);
                                    }
                                    handleConnectionLoss(ws);
                                    ws.close();
                                });

                                sendAuth(ws);
                                authTimerId[0] = vertx.setTimer(5000, timerId -> {
                                    if (!completed[0]) {
                                        completed[0] = true;
                                        promise.fail("FRP auth timeout");
                                        ws.close();
                                    }
                                });
                                ws.frameHandler(frame -> {
                                    OperationType operationType = frameOperationType(frame);
                                    handleServerFrame(ws, frame);
                                    if (isAuthenticated(ws) && !completed[0]) {
                                        completed[0] = true;
                                        vertx.cancelTimer(authTimerId[0]);
                                        promise.complete();
                                    } else if (operationType == OperationType.AUTH_FAIL && !completed[0]) {
                                        completed[0] = true;
                                        vertx.cancelTimer(authTimerId[0]);
                                        promise.fail("FRP auth failed");
                                    }
                                });
                                lock.release();
                            })
                            .onFailure(t -> {
                                LOGGER.error("Failed to connect to FRP server", t);
                                promise.fail(t);
                                lock.release();
                            });
                })
                .onFailure(t -> {
                    LOGGER.error("Failed to acquire FRP connection lock", t);
                    promise.fail(t);
                });

        return promise.future();
    }

    private OperationType frameOperationType(WebSocketFrame frame) {
        if (!frame.isBinary()) {
            return null;
        }
        Buffer data = frame.binaryData();
        if (data.length() < MessageUtil.CONTROL_WIDTH) {
            return null;
        }
        try {
            return MessageUtil.getOperationType(data);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isAuthenticated(WebSocket socket) {
        return socket == controlSocket && authenticated && !socket.isClosed();
    }

    private void handleServerFrame(WebSocket sourceSocket, WebSocketFrame frame) {
        try {
            if (sourceSocket != controlSocket) {
                LOGGER.debug("Ignoring frame from stale FRP control session");
                return;
            }
            // 只处理二进制帧
            if (!frame.isBinary()) return;

            Buffer data = frame.binaryData();

            if (data.length() < MessageUtil.CONTROL_WIDTH) {
                LOGGER.error("Invalid frame length from server: {}", data.length());
                return;
            }

            OperationType operationType = MessageUtil.getOperationType(data);
            if (!authenticated) {
                handleAuthFrame(sourceSocket, operationType, data);
                return;
            }

            if (MessageUtil.isControlOperation(operationType)) {
                handleAuthFrame(sourceSocket, operationType, data);
                return;
            }

            if (data.length() < OPERATION_WIDTH) {
                LOGGER.error("Invalid transfer frame length from server: {}", data.length());
                return;
            }

            String requestId = MessageUtil.getRequestId(data);
            Buffer payload = MessageUtil.getPayload(data);

            switch (operationType) {
                case CONNECT:
                    LOGGER.debug("Received CONNECT command for request: {}", requestId);
                    handleConnectRequest(sourceSocket, requestId);
                    break;

                case DATA:
                    LOGGER.debug("Received DATA for request: {} ({} bytes)", requestId, payload != null ? payload.length() : 0);
                    handleDataRequest(requestId, payload);
                    break;

                case CLOSE:
                    LOGGER.debug("Received CLOSE command for request: {}", requestId);
                    handleCloseRequest(requestId);
                    break;

                default:
                    LOGGER.warn("Unknown op code {} from server", operationType);
            }
        } catch (Exception ex) {
            LOGGER.error("Error processing server frame", ex);
        }
    }

    private void sendAuth(WebSocket ws) {
        JsonObject payload = new JsonObject()
                .put("version", PROTOCOL_VERSION)
                .put("token", agent.auth().token())
                .put("agentName", agent.agentName());
        ws.writeBinaryMessage(MessageUtil.buildControlMessage(OperationType.AUTH, payload));
    }

    private void handleAuthFrame(WebSocket sourceSocket, OperationType operationType, Buffer data) {
        if (sourceSocket != controlSocket) {
            return;
        }
        if (operationType == OperationType.AUTH_OK) {
            authenticated = true;
            JsonObject payload = MessageUtil.getControlPayload(data);
            LOGGER.info("FRP auth success, sessionId={}", payload.getString("sessionId"));
            return;
        }
        if (operationType == OperationType.AUTH_FAIL) {
            LOGGER.error("FRP auth failed");
            sourceSocket.close();
            return;
        }
        LOGGER.warn("Ignoring {} before FRP auth success", operationType);
    }

    private void handleConnectRequest(WebSocket sourceSocket, String requestId) {
        // 确保请求 ID 唯一。重复 CONNECT 不能替换正在使用的连接。
        if (pendingRequests.containsKey(requestId)) {
            LOGGER.warn("Request ID {} already exists", requestId);
            return;
        }

        ProxyServer proxyServer = agent.proxy();
        RequestConnection request = new RequestConnection(requestId, sourceSocket);
        pendingRequests.put(requestId, request);

        LOGGER.debug("Try connect to target service for request: {}", requestId);

        connectTarget(proxyServer)
                .onSuccess(socket -> onTargetConnected(request, socket))
                .onFailure(t -> {
                    LOGGER.error("Failed to connect to target service for request: {}", requestId, t);
                    failRequest(request, t);
                });
    }

    private NetClient targetClient() {
        if (targetClient == null) {
            targetClient = vertx.createNetClient();
        }
        return targetClient;
    }

    /**
     * Opens the per-request connection to the configured target. Kept overridable so
     * transport integrations can control connection establishment without changing
     * request lifecycle handling.
     */
    protected Future<NetSocket> connectTarget(ProxyServer proxyServer) {
        return targetClient().connect(proxyServer.port(), proxyServer.host());
    }

    private void handleDataRequest(String requestId, Buffer data) {
        RequestConnection request = pendingRequests.get(requestId);
        if (request == null || request.closed) {
            LOGGER.warn("Received data for unknown request: {}", requestId);
            return;
        }
        if (data == null || data.length() == 0) {
            return;
        }
        if (request.bufferedBytes + data.length() > MAX_PENDING_REQUEST_BYTES) {
            LOGGER.warn("Pending data limit exceeded for request: {}", requestId);
            failRequest(request, null);
            return;
        }
        request.pendingData.addLast(data.copy());
        request.bufferedBytes += data.length();
        flushPendingWrites(request);
    }

    private void handleCloseRequest(String requestId) {
        RequestConnection request = pendingRequests.get(requestId);
        if (request != null) {
            closeRequestConnection(request);
        }
    }

    private void onTargetConnected(RequestConnection request, NetSocket socket) {
        if (!isCurrentRequest(request)) {
            socket.close();
            return;
        }
        LOGGER.debug("Connected to target service for request: {}", request.requestId);
        request.targetSocket = socket;
        socket.handler(data -> forwardTargetData(request, data));
        socket.closeHandler(v -> {
            LOGGER.debug("Target service connection closed for request: {}", request.requestId);
            failRequest(request, null);
        });
        socket.exceptionHandler(ex -> {
            LOGGER.error("Target service connection error for request: {}", request.requestId, ex);
            failRequest(request, ex);
        });
        flushPendingWrites(request);
    }

    private void flushPendingWrites(RequestConnection request) {
        if (!isCurrentRequest(request) || request.targetSocket == null || request.writeInProgress || request.pendingData.isEmpty()) {
            return;
        }
        Buffer next = request.pendingData.peekFirst();
        request.writeInProgress = true;
        try {
            request.targetSocket.write(next).onComplete(result -> {
                if (!isCurrentRequest(request)) {
                    return;
                }
                request.writeInProgress = false;
                if (result.failed()) {
                    LOGGER.error("Failed to write data to target service for request: {}", request.requestId, result.cause());
                    failRequest(request, result.cause());
                    return;
                }
                request.pendingData.removeFirst();
                request.bufferedBytes -= next.length();
                LOGGER.debug("Forwarded {} bytes to target service for request {}", next.length(), request.requestId);
                flushPendingWrites(request);
            });
        } catch (Exception ex) {
            request.writeInProgress = false;
            LOGGER.error("Failed to write data to target service for request: {}", request.requestId, ex);
            failRequest(request, ex);
        }
    }

    private void closeRequestConnection(RequestConnection request) {
        closeRequest(request, false);
    }

    private void closeRequest(RequestConnection request, boolean notifyServer) {
        if (request.closed) {
            return;
        }
        request.closed = true;
        if (pendingRequests.get(request.requestId) == request) {
            pendingRequests.remove(request.requestId);
        }
        request.pendingData.clear();
        request.bufferedBytes = 0;
        NetSocket targetSocket = request.targetSocket;
        if (targetSocket != null) {
            try {
                targetSocket.close();
                LOGGER.debug("Closed target service connection for request: {}", request.requestId);
            } catch (Exception ignore) {
            }
        }
        if (notifyServer) {
            notifyServerOfConnectionFailure(request);
        }
    }

    private void failRequest(RequestConnection request, Throwable cause) {
        if (!isCurrentRequest(request)) {
            return;
        }
        closeRequest(request, true);
    }

    private boolean isCurrentRequest(RequestConnection request) {
        return !request.closed && pendingRequests.get(request.requestId) == request;
    }

    private void notifyServerOfConnectionFailure(RequestConnection request) {
        if (request.closeNotified) {
            return;
        }
        request.closeNotified = true;
        try {
            if (ownsActiveControlSession(request)) {
                request.controlSocket.writeBinaryMessage(MessageUtil.buildOperationMessage(request.requestId, OperationType.CLOSE))
                        .onFailure(ex -> LOGGER.debug("Failed to notify server of request closure", ex));
                LOGGER.debug("Notified server of connection failure for request: {}", request.requestId);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to notify server of connection failure", ex);
        }
    }

    private boolean isControlSessionAvailable(RequestConnection request) {
        return isCurrentRequest(request) && ownsActiveControlSession(request);
    }

    private boolean ownsActiveControlSession(RequestConnection request) {
        return request.controlSocket == controlSocket
                && authenticated
                && controlSocket != null
                && !controlSocket.isClosed();
    }

    private void forwardTargetData(RequestConnection request, Buffer data) {
        if (!isControlSessionAvailable(request)) {
            LOGGER.warn("Control channel not available, cannot send data for request: {}", request.requestId);
            closeRequestConnection(request);
            return;
        }
        try {
            while ((data.length() + OPERATION_WIDTH) > MAX_WEBSOCKET_FRAME_SIZE) {
                sendDataToServer(request, data.slice(0, MAX_WEBSOCKET_FRAME_SIZE - OPERATION_WIDTH));
                data = data.slice(MAX_WEBSOCKET_FRAME_SIZE - OPERATION_WIDTH, data.length());
            }
            sendDataToServer(request, data);
        } catch (Exception ex) {
            LOGGER.error("Failed to send data to server for request: {}", request.requestId, ex);
            failRequest(request, ex);
        }
    }

    private void sendDataToServer(RequestConnection request, Buffer data) {
        if (!isControlSessionAvailable(request)) {
            closeRequestConnection(request);
            return;
        }
        request.controlSocket.writeBinaryMessage(MessageUtil.buildDataMessage(request.requestId, data))
                .onFailure(ex -> {
                    LOGGER.error("Failed to send data to server for request: {}", request.requestId, ex);
                    failRequest(request, ex);
                });
        LOGGER.debug("Sent {} bytes to server for request {}", data.length(), request.requestId);
    }

    private void handleConnectionLoss(WebSocket lostSocket) {
        if (lostSocket != controlSocket) {
            return;
        }

        LOGGER.error("Connection to FRP server lost");
        controlSocket = null;
        authenticated = false;
        for (RequestConnection request : pendingRequests.values().toArray(new RequestConnection[0])) {
            closeRequestConnection(request);
        }
    }

    private void closeRequestsForControlSocket(WebSocket socket) {
        for (RequestConnection request : pendingRequests.values().toArray(new RequestConnection[0])) {
            if (request.controlSocket == socket) {
                closeRequestConnection(request);
            }
        }
    }

    private WebSocketClientOptions options(FrpTunnel server) {
        return new WebSocketClientOptions()
                .setDefaultHost(server.host())
                .setDefaultPort(server.port())
                .setTcpKeepAlive(true)
                .setMaxFrameSize(MAX_WEBSOCKET_FRAME_SIZE);
    }

    @Override
    public void stop() {
        LOGGER.info("Stopping agent linker");
        stopped = true;

        if (liveCheckTimerId != -1) {
            vertx.cancelTimer(liveCheckTimerId);
            liveCheckTimerId = -1;
        }
        if (heartbeatTimerId != -1) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }

        // 关闭控制通道
        if (controlSocket != null) {
            try {
                controlSocket.close();
            } catch (Exception ignore) {
            }
            controlSocket = null;
        }

        // 关闭所有目标服务连接
        for (RequestConnection request : pendingRequests.values().toArray(new RequestConnection[0])) {
            closeRequestConnection(request);
        }
        if (targetClient != null) {
            targetClient.close();
            targetClient = null;
        }
        if (controlClient != null) {
            controlClient.close();
            controlClient = null;
        }
    }

    private static final class RequestConnection {
        private final String requestId;
        private final WebSocket controlSocket;
        private final ArrayDeque<Buffer> pendingData = new ArrayDeque<>();
        private NetSocket targetSocket;
        private int bufferedBytes;
        private boolean writeInProgress;
        private boolean closed;
        private boolean closeNotified;

        private RequestConnection(String requestId, WebSocket controlSocket) {
            this.requestId = requestId;
            this.controlSocket = controlSocket;
        }
    }
}
