package net.ximatai.frp.agent.verticle;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.net.NetSocket;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.shared.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AgentLinkerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLinkerVerticle.class);

    private static final long LIVE_CHECK_INTERVAL = 10000;      // 10秒保活检查

    private final Agent agent;
    private WebSocket controlSocket;

    // 存储请求映射 (requestId -> NetSocket连接到目标服务)
    private final Map<String, NetSocket> pendingRequests = new HashMap<>();

    public AgentLinkerVerticle(Agent agent) {
        this.agent = agent;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        connectToFrpTunnel()
                .onComplete(startPromise);

        vertx.setPeriodic(LIVE_CHECK_INTERVAL, timerId -> {
            if (this.controlSocket == null) {
                connectToFrpTunnel();
            }
        });

        vertx.setPeriodic(Constants.HEARTBEAT_INTERVAL, id -> {
            try {
                if (controlSocket == null) return;

                controlSocket.writePing(Buffer.buffer("ping-" + System.currentTimeMillis()));
                LOGGER.trace("Sent heartbeat PING to server");
            } catch (Exception ex) {
                LOGGER.error("Failed to send heartbeat", ex);
                handleConnectionLoss();
            }
        });
    }

    private void setControlSocket(WebSocket controlSocket) {
        if (this.controlSocket != null && !this.controlSocket.isClosed()) {
            this.controlSocket.close();
        }
        this.controlSocket = controlSocket;
    }

    private Future<Void> connectToFrpTunnel() {
        Promise<Void> promise = Promise.promise();

        FrpTunnel frpTunnel = agent.frpTunnel();

        LOGGER.info("Connecting to FRP tunnel at {}:{}", frpTunnel.host(), frpTunnel.port());

        vertx.sharedData().getLock("frp-agent-lock")
                .onSuccess(lock -> {
                    vertx.createWebSocketClient(options(frpTunnel))
                            .connect("/")
                            .onSuccess(ws -> {
                                LOGGER.info("Successfully connected to FRP server");

                                // 保存控制通道socket
                                setControlSocket(ws);

                                // 设置帧处理器
                                ws.frameHandler(this::handleServerFrame);

                                // 设置关闭处理器
                                ws.closeHandler(v -> {
                                    LOGGER.warn("Connection to FRP server closed");
                                    handleConnectionLoss();
                                });

                                // 设置异常处理器
                                ws.exceptionHandler(ex -> {
                                    LOGGER.error("WebSocket connection error", ex);
                                    ws.close();
                                    handleConnectionLoss();
                                });

                                promise.complete();
                                lock.release();
                            })
                            .onFailure(t -> {
                                LOGGER.error("Failed to connect to FRP server", t);
                                promise.fail(t);
                                lock.release();
                            });
                });

        return promise.future();
    }

    private void handleServerFrame(WebSocketFrame frame) {
        try {
            // 只处理二进制帧
            if (!frame.isBinary()) return;

            Buffer data = frame.binaryData();

            // 检查最小长度（至少包含操作码+UUID长度）
            if (data.length() < 17) {
                LOGGER.error("Invalid frame length from server: {}", data.length());
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

            switch (opCode) {
                case Constants.CONNECT:
                    LOGGER.debug("Received CONNECT command for request: {}", requestId);
                    handleConnectRequest(requestId);
                    break;

                case Constants.DATA:
                    LOGGER.debug("Received DATA for request: {} ({} bytes)", requestId, payload != null ? payload.length() : 0);
                    handleDataRequest(requestId, payload);
                    break;

                case Constants.CLOSE:
                    LOGGER.debug("Received CLOSE command for request: {}", requestId);
                    handleCloseRequest(requestId);
                    break;

                default:
                    LOGGER.warn("Unknown op code {} from server", opCode);
            }
        } catch (Exception ex) {
            LOGGER.error("Error processing server frame", ex);
        }
    }

    private void handleConnectRequest(String requestId) {

        // 确保请求ID唯一（不应该已存在）
        if (pendingRequests.containsKey(requestId)) {
            LOGGER.warn("Request ID {} already exists", requestId);
        }

        ProxyServer proxyServer = agent.proxy();

        LOGGER.debug("Try connect to target service for request: {}", requestId);

        vertx.sharedData().getLock(requestId)
                .onSuccess(lock -> {
                    // 连接到目标服务
                    vertx.createNetClient()
                            .connect(proxyServer.port(), proxyServer.host())
                            .onSuccess(socket -> {
                                LOGGER.debug("Connected to target service for request: {}", requestId);

                                // 保存到目标服务的连接
                                pendingRequests.put(requestId, socket);

                                // 处理目标服务的数据
                                socket.handler(data -> {
                                    try {
                                        while ((data.length() + Constants.CONTROL_WIDTH) > Constants.MAX_WEBSOCKET_FRAME_SIZE) {
                                            sendDataToServer(requestId, data.slice(0, Constants.MAX_WEBSOCKET_FRAME_SIZE - Constants.CONTROL_WIDTH));
                                            data = data.slice(Constants.MAX_WEBSOCKET_FRAME_SIZE - Constants.CONTROL_WIDTH, data.length());
                                        }

                                        sendDataToServer(requestId, data);
                                    } catch (Exception ex) {
                                        LOGGER.error("Error sending data to server", ex);
                                        closeRequestConnection(requestId);
                                    }
                                });

                                // 处理目标服务关闭
                                socket.closeHandler(v -> {
                                    LOGGER.debug("Target service connection closed for request: {}", requestId);
                                    closeRequestConnection(requestId);
                                    notifyServerOfConnectionFailure(requestId);
                                });

                                // 处理目标服务异常
                                socket.exceptionHandler(ex -> {
                                    LOGGER.error("Target service connection error for request: {}", requestId, ex);
                                    closeRequestConnection(requestId);
                                    notifyServerOfConnectionFailure(requestId);
                                });

                                lock.release();
                            })
                            .onFailure(t -> {
                                LOGGER.error("Failed to connect to target service for request: {}", requestId, t);
                                notifyServerOfConnectionFailure(requestId);
                                lock.release();
                            });
                })
                .onFailure(t -> {
                    LOGGER.error("Failed to acquire lock for request: {}", requestId, t);
                });

    }

    private void handleDataRequest(String requestId, Buffer data) {
        vertx.sharedData().getLock(requestId)
                .onSuccess(lock -> {
                    NetSocket targetSocket = pendingRequests.get(requestId);

                    lock.release();

                    if (targetSocket == null) {
                        LOGGER.warn("Received data for unknown request: {}", requestId);
                        return;
                    }

                    try {
                        if (data != null && data.length() > 0) {
                            targetSocket.write(data);
                            LOGGER.debug("Forwarded {} bytes to target service for request {}", data.length(), requestId);
                        }
                    } catch (Exception ex) {
                        LOGGER.error("Failed to write data to target service for request: {}", requestId, ex);
                        closeRequestConnection(requestId);
                    }
                })
                .onFailure(t -> {
                    LOGGER.error("Failed to acquire lock for request: {}", requestId, t);
                });

    }

    private void handleCloseRequest(String requestId) {
        closeRequestConnection(requestId);
    }

    private void closeRequestConnection(String requestId) {
        NetSocket targetSocket = pendingRequests.remove(requestId);
        if (targetSocket != null) {
            try {
                targetSocket.close();
                LOGGER.debug("Closed target service connection for request: {}", requestId);
            } catch (Exception ignore) {
            }
        }
    }

    private void notifyServerOfConnectionFailure(String requestId) {
        // 创建关闭帧通知服务器
        Buffer frame = createCloseFrame(requestId);

        try {
            if (controlSocket != null && !controlSocket.isClosed()) {
                controlSocket.writeBinaryMessage(frame);
                LOGGER.debug("Notified server of connection failure for request: {}", requestId);
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to notify server of connection failure", ex);
        } finally {
            // 确保从pendingRequests中移除
            pendingRequests.remove(requestId);
        }
    }

    private void sendDataToServer(String requestId, Buffer data) {
        if (controlSocket == null || controlSocket.isClosed()) {
            LOGGER.warn("Control channel not available, cannot send data for request: {}", requestId);
            closeRequestConnection(requestId);
            return;
        }

        // 构建数据帧：操作码 + 请求ID + 有效载荷
        Buffer frame = Buffer.buffer(17 + data.length());
        frame.appendByte(Constants.DATA);
        appendUUID(frame, requestId);
        frame.appendBuffer(data);

        try {
            controlSocket.writeBinaryMessage(frame);
            LOGGER.debug("Sent {} bytes to server for request {}", data.length(), requestId);
        } catch (Exception ex) {
            LOGGER.error("Failed to send data to server for request: {}", requestId, ex);
            closeRequestConnection(requestId);
        }
    }

    private void handleConnectionLoss() {

        LOGGER.error("Connection to FRP server lost");

        // 关闭所有目标服务连接
        for (NetSocket socket : pendingRequests.values()) {
            try {
                socket.close();
            } catch (Exception ignore) {
            }
        }

        pendingRequests.clear();

        setControlSocket(null);
    }

    private Buffer createCloseFrame(String requestId) {
        Buffer frame = Buffer.buffer(17);
        frame.appendByte(Constants.CLOSE);
        appendUUID(frame, requestId);
        return frame;
    }

    private void appendUUID(Buffer buffer, String requestId) {
        UUID uuid = UUID.fromString(requestId);
        buffer.appendLong(uuid.getMostSignificantBits());
        buffer.appendLong(uuid.getLeastSignificantBits());
    }

    private WebSocketClientOptions options(FrpTunnel server) {
        return new WebSocketClientOptions()
                .setDefaultHost(server.host())
                .setDefaultPort(server.port())
                .setTcpKeepAlive(true)
                .setMaxFrameSize(Constants.MAX_WEBSOCKET_FRAME_SIZE);
    }

    @Override
    public void stop() {
        LOGGER.info("Stopping agent linker");

        // 关闭控制通道
        if (controlSocket != null) {
            try {
                controlSocket.close();
            } catch (Exception ignore) {
            }
            controlSocket = null;
        }

        // 关闭所有目标服务连接
        for (NetSocket socket : pendingRequests.values()) {
            try {
                socket.close();
            } catch (Exception ignore) {
            }
        }
        pendingRequests.clear();

    }
}
