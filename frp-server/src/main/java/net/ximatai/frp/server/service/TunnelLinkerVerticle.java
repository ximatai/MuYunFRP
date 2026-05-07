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
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import net.ximatai.frp.common.MessageUtil;
import net.ximatai.frp.common.OperationType;
import net.ximatai.frp.server.config.Tunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.ximatai.frp.common.MessageUtil.OPERATION_WIDTH;

public class TunnelLinkerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelLinkerVerticle.class);

    private static final long HEARTBEAT_INTERVAL = 30000;
    public static final long AUTH_TIMEOUT = Long.getLong("muyun.frp.auth.timeout", 5000L);
    private static final int REQUEST_TIMEOUT = 60000 * 60;
    private static final int MAX_WEBSOCKET_FRAME_SIZE = 65536;
    private static final int PROTOCOL_VERSION = 1;

    private final Vertx vertx;
    private final Tunnel tunnel;
    private final TunnelRuntimeRegistry runtimeRegistry;

    private volatile AgentSession activeSession;
    private HttpServer agentServer;
    private NetServer publicServer;
    private volatile boolean stopping;
    private final Map<String, AgentSession> agentSessions = new ConcurrentHashMap<>();
    private final Map<String, RequestContext> pendingRequests = new ConcurrentHashMap<>();

    public TunnelLinkerVerticle(Vertx vertx, Tunnel tunnel) {
        this(vertx, tunnel, new TunnelRuntimeRegistry());
    }

    public TunnelLinkerVerticle(Vertx vertx, Tunnel tunnel, TunnelRuntimeRegistry runtimeRegistry) {
        this.vertx = vertx;
        this.tunnel = tunnel;
        this.runtimeRegistry = runtimeRegistry;
        this.runtimeRegistry.registerTunnel(tunnel);
    }

    @Override
    public void start(Promise<Void> startPromise) {
        LOGGER.info("Try To Link {}, OpenPort is {}, AgentPort is {}",
                tunnel.name(), tunnel.openPort(), tunnel.agentPort());

        createAgentServer(tunnel.agentPort())
                .compose(v -> createPublicServer(tunnel.openPort()))
                .onSuccess(v -> {
                    LOGGER.info("Link {} Success", tunnel.name());
                    startPromise.complete();
                })
                .onFailure(throwable -> {
                    LOGGER.error("Link {} Failed", tunnel.name(), throwable);
                    stopOpenedServers().onComplete(v -> startPromise.fail(throwable));
                });
    }

    private Future<Void> createAgentServer(int port) {
        Promise<Void> promise = Promise.promise();
        HttpServerOptions options = new HttpServerOptions()
                .setRegisterWebSocketWriteHandlers(true)
                .setMaxWebSocketFrameSize(MAX_WEBSOCKET_FRAME_SIZE);

        HttpServer server = vertx.createHttpServer(options);
        server.webSocketHandler(webSocket -> {
                    AgentSession session = new AgentSession(UUID.randomUUID().toString(), webSocket);
                    agentSessions.put(session.sessionId, session);
                    LOGGER.info("FRP Agent connected before auth: {} @ {}", session.sessionId, webSocket.remoteAddress());

                    session.authTimerId = vertx.setTimer(AUTH_TIMEOUT, id -> {
                        if (!session.authenticated) {
                            LOGGER.warn("FRP Agent auth timeout: {}", session.sessionId);
                            closeWebSocket(webSocket);
                        }
                    });
                    session.heartbeatTimerId = setupHeartbeat(session);

                    webSocket.frameHandler(frame -> handleAgentFrame(session, frame));
                    webSocket.closeHandler(v -> handleSessionClosed(session));
                    webSocket.exceptionHandler(ex -> {
                        LOGGER.error("WebSocket error for agent session {}", session.sessionId, ex);
                        closeWebSocket(webSocket);
                    });
                })
                .invalidRequestHandler(request -> LOGGER.error("Invalid request: {}", request.uri()))
                .exceptionHandler(err -> LOGGER.error("Server error", err))
                .listen(port)
                .onSuccess(s -> {
                    agentServer = s;
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
                    AgentSession session = authenticatedSession();
                    if (session == null) {
                        LOGGER.error("No authenticated agent for tunnel {}, closing user connection", tunnel.name());
                        userSocket.close();
                        return;
                    }

                    String requestId = UUID.randomUUID().toString();
                    LOGGER.debug("New user request: {}", requestId);

                    RequestContext context = new RequestContext(requestId, session.sessionId, userSocket);
                    pendingRequests.put(requestId, context);
                    updateActiveConnections(session.sessionId);

                    context.timeoutId = vertx.setTimer(REQUEST_TIMEOUT, tid -> {
                        RequestContext removed = pendingRequests.remove(requestId);
                        if (removed != null) {
                            removed.closed = true;
                            userSocket.close();
                            sendCloseSignalToAgent(removed);
                            updateActiveConnections(removed.sessionId);
                            LOGGER.warn("Request timed out: {}", requestId);
                        }
                    });

                    if (!forwardRequestToAgent(context)) {
                        cleanupRequest(requestId, false);
                        userSocket.close();
                        LOGGER.error("No available authenticated agent for request: {}", requestId);
                        return;
                    }

                    userSocket.handler(data -> {
                        while ((data.length() + OPERATION_WIDTH) > MAX_WEBSOCKET_FRAME_SIZE) {
                            handleUserData(requestId, data.slice(0, MAX_WEBSOCKET_FRAME_SIZE - OPERATION_WIDTH));
                            data = data.slice(MAX_WEBSOCKET_FRAME_SIZE - OPERATION_WIDTH, data.length());
                        }
                        handleUserData(requestId, data);
                    });

                    userSocket.closeHandler(v -> {
                        context.closed = true;
                        cleanupRequest(requestId, true);
                    });

                    userSocket.exceptionHandler(ex -> {
                        context.closed = true;
                        cleanupRequest(requestId, true);
                        LOGGER.error("User connection exception: {}", requestId, ex);
                    });
                })
                .listen(port)
                .onSuccess(server -> {
                    publicServer = server;
                    LOGGER.info("Public server listening on port {}", port);
                    promise.complete();
                })
                .onFailure(throwable -> {
                    LOGGER.error("Public server failed to start on port {}", port, throwable);
                    promise.fail(throwable);
                });

        return promise.future();
    }

    private void handleAgentFrame(AgentSession session, WebSocketFrame frame) {
        if (frame.isClose()) {
            closeWebSocket(session.webSocket);
            return;
        }
        if (frame.isPing()) {
            session.lastSeenAt = Instant.now();
            runtimeRegistry.touchAgent(tunnel, session.sessionId);
            session.webSocket.writePong(Buffer.buffer("pong"));
            return;
        }
        if (frame.type().equals(WebSocketFrameType.PONG)) {
            session.lastSeenAt = Instant.now();
            runtimeRegistry.touchAgent(tunnel, session.sessionId);
            return;
        }
        if (!frame.isBinary()) {
            return;
        }

        Buffer data = frame.binaryData();
        if (data.length() < MessageUtil.CONTROL_WIDTH) {
            LOGGER.error("Invalid frame length from agent session {}: {}", session.sessionId, data.length());
            return;
        }

        try {
            OperationType operationType = MessageUtil.getOperationType(data);
            if (!session.authenticated) {
                if (operationType == OperationType.AUTH) {
                    handleAuth(session, data);
                } else {
                    LOGGER.warn("Ignoring unauthenticated {} frame from session {}", operationType, session.sessionId);
                }
                return;
            }

            if (MessageUtil.isControlOperation(operationType)) {
                LOGGER.warn("Unexpected control frame {} from authenticated session {}", operationType, session.sessionId);
                return;
            }
            if (data.length() < OPERATION_WIDTH) {
                LOGGER.error("Invalid transfer frame length from agent session {}: {}", session.sessionId, data.length());
                return;
            }

            String requestId = MessageUtil.getRequestId(data);
            Buffer payload = MessageUtil.getPayload(data);
            RequestContext context = pendingRequests.get(requestId);
            if (context == null || context.closed || !context.sessionId.equals(session.sessionId) || !session.active) {
                LOGGER.warn("Request context not found, closed, or stale: {}", requestId);
                return;
            }

            switch (operationType) {
                case DATA:
                    context.socket.write(payload);
                    LOGGER.debug("Forwarded {} bytes to user for request {}", payload.length(), requestId);
                    break;
                case CLOSE:
                    RequestContext removed = cleanupRequest(requestId, false);
                    if (removed != null) {
                        removed.socket.close();
                        LOGGER.debug("Closed user connection: {}", requestId);
                    }
                    break;
                default:
                    LOGGER.warn("Unknown op code {} from agent session {}", operationType, session.sessionId);
            }
        } catch (Exception ex) {
            LOGGER.error("Error processing agent frame", ex);
        }
    }

    private void handleAuth(AgentSession session, Buffer data) {
        JsonObject authPayload;
        try {
            authPayload = MessageUtil.getControlPayload(data);
        } catch (Exception ex) {
            rejectAuth(session);
            return;
        }
        String token = authPayload.getString("token");
        String agentName = authPayload.getString("agentName");
        int version = authPayload.getInteger("version", -1);

        if (version != PROTOCOL_VERSION || agentName == null || agentName.isBlank()) {
            rejectAuth(session);
            return;
        }

        vertx.executeBlocking(() -> tunnel.verifyToken(token), false)
                .onSuccess(verified -> {
                    if (session.webSocket.isClosed() || session.authenticated) {
                        return;
                    }
                    if (!verified) {
                        rejectAuth(session);
                        return;
                    }
                    acceptAuth(session, agentName);
                })
                .onFailure(ex -> rejectAuth(session));
    }

    private void acceptAuth(AgentSession session, String agentName) {
        vertx.cancelTimer(session.authTimerId);
        session.agentName = agentName;
        session.authenticated = true;
        session.active = true;
        session.connectedAt = Instant.now();
        session.lastSeenAt = session.connectedAt;

        AgentSession oldSession = activeSession;
        if (oldSession != null && oldSession != session) {
            replaceOldSession(oldSession, session);
        }

        activeSession = session;
        runtimeRegistry.markAgentOnline(tunnel, agentName, session.sessionId);
        session.webSocket.writeBinaryMessage(MessageUtil.buildControlMessage(
                OperationType.AUTH_OK,
                new JsonObject()
                        .put("version", PROTOCOL_VERSION)
                        .put("sessionId", session.sessionId)
                        .put("message", "ok")
        ));
        LOGGER.info("FRP Agent authenticated for tunnel {}: agentName={}, sessionId={}",
                tunnel.name(), agentName, session.sessionId);
    }

    private void rejectAuth(AgentSession session) {
        LOGGER.warn("FRP Agent auth failed for tunnel {} session {}", tunnel.name(), session.sessionId);
        session.webSocket.writeBinaryMessage(MessageUtil.buildControlMessage(
                OperationType.AUTH_FAIL,
                new JsonObject().put("version", PROTOCOL_VERSION).put("message", "auth failed")
        )).onComplete(v -> closeWebSocket(session.webSocket));
    }

    private void replaceOldSession(AgentSession oldSession, AgentSession newSession) {
        LOGGER.warn("Replacing tunnel {} agent session old={}, new={}",
                tunnel.name(), oldSession.sessionId, newSession.sessionId);
        oldSession.active = false;
        closeRequestsForSession(oldSession.sessionId);
        closeWebSocket(oldSession.webSocket);
    }

    private void handleUserData(String requestId, Buffer data) {
        RequestContext context = pendingRequests.get(requestId);
        if (context == null || context.closed) {
            return;
        }
        AgentSession session = authenticatedSession(context.sessionId);
        if (session == null) {
            cleanupRequest(requestId, false);
            context.socket.close();
            LOGGER.error("No active agent while forwarding data for {}", requestId);
            return;
        }

        try {
            session.webSocket.writeBinaryMessage(MessageUtil.buildDataMessage(requestId, data));
            LOGGER.debug("Forwarded {} bytes to agent {} for request {}",
                    data.length(), session.sessionId, requestId);
        } catch (Exception ex) {
            LOGGER.error("Failed to forward data to agent {}", session.sessionId, ex);
        }
    }

    private boolean forwardRequestToAgent(RequestContext context) {
        AgentSession session = authenticatedSession(context.sessionId);
        if (session == null) {
            return false;
        }
        try {
            session.webSocket.writeBinaryMessage(MessageUtil.buildOperationMessage(context.requestId, OperationType.CONNECT));
            return true;
        } catch (Exception ex) {
            LOGGER.error("Failed to notify agent about new request", ex);
            return false;
        }
    }

    private void sendCloseSignalToAgent(RequestContext context) {
        AgentSession session = authenticatedSession(context.sessionId);
        if (session == null) {
            return;
        }
        try {
            session.webSocket.writeBinaryMessage(MessageUtil.buildOperationMessage(context.requestId, OperationType.CLOSE));
            LOGGER.debug("Sent CLOSE signal to agent {} for request {}", session.sessionId, context.requestId);
        } catch (Exception ex) {
            LOGGER.error("Failed to send close signal to agent {}", session.sessionId, ex);
        }
    }

    private RequestContext cleanupRequest(String requestId, boolean notifyAgent) {
        RequestContext context = pendingRequests.remove(requestId);
        if (context == null) {
            return null;
        }
        vertx.cancelTimer(context.timeoutId);
        if (notifyAgent) {
            sendCloseSignalToAgent(context);
        }
        updateActiveConnections(context.sessionId);
        return context;
    }

    private void closeRequestsForSession(String sessionId) {
        pendingRequests.entrySet().removeIf(entry -> {
            RequestContext context = entry.getValue();
            if (!context.sessionId.equals(sessionId)) {
                return false;
            }
            vertx.cancelTimer(context.timeoutId);
            context.closed = true;
            context.socket.close();
            return true;
        });
        updateActiveConnections(sessionId);
    }

    private AgentSession authenticatedSession() {
        AgentSession session = activeSession;
        if (session == null || !session.authenticated || !session.active || session.webSocket.isClosed()) {
            return null;
        }
        return session;
    }

    private AgentSession authenticatedSession(String sessionId) {
        AgentSession session = authenticatedSession();
        if (session == null || !session.sessionId.equals(sessionId)) {
            return null;
        }
        return session;
    }

    private long setupHeartbeat(AgentSession session) {
        return vertx.setPeriodic(HEARTBEAT_INTERVAL, id -> {
            if (session.webSocket.isClosed()) {
                vertx.cancelTimer(id);
                return;
            }
            try {
                session.webSocket.writePing(Buffer.buffer("ping-" + System.currentTimeMillis()));
                LOGGER.trace("Sent PING to agent session {}", session.sessionId);
            } catch (Exception ex) {
                LOGGER.error("Heartbeat failed for agent session {}", session.sessionId, ex);
                closeWebSocket(session.webSocket);
            }
        });
    }

    private void handleSessionClosed(AgentSession session) {
        vertx.cancelTimer(session.authTimerId);
        vertx.cancelTimer(session.heartbeatTimerId);
        session.active = false;
        agentSessions.remove(session.sessionId);
        if (activeSession == session) {
            activeSession = null;
            closeRequestsForSession(session.sessionId);
            runtimeRegistry.markAgentOffline(tunnel, session.sessionId);
        }
        LOGGER.warn("FRP Agent disconnected: {}", session.sessionId);
    }

    private void updateActiveConnections(String sessionId) {
        AgentSession session = activeSession;
        if (session == null || !session.sessionId.equals(sessionId)) {
            return;
        }
        int count = (int) pendingRequests.values().stream()
                .filter(context -> context.sessionId.equals(sessionId))
                .count();
        runtimeRegistry.updateActiveConnections(tunnel, sessionId, count);
    }

    private void closeWebSocket(ServerWebSocket webSocket) {
        try {
            if (!webSocket.isClosed()) {
                webSocket.close();
            }
        } catch (Exception ignore) {
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        stopping = true;
        AgentSession session = activeSession;
        activeSession = null;
        if (session != null) {
            runtimeRegistry.markAgentOffline(tunnel, session.sessionId);
        }
        for (AgentSession agentSession : new ArrayList<>(agentSessions.values())) {
            agentSession.active = false;
            closeWebSocket(agentSession.webSocket);
        }
        agentSessions.clear();
        for (RequestContext context : new ArrayList<>(pendingRequests.values())) {
            vertx.cancelTimer(context.timeoutId);
            context.closed = true;
            context.socket.close();
        }
        pendingRequests.clear();
        updateActiveConnectionsAfterStop();

        stopOpenedServers().onComplete(ar -> {
            if (ar.succeeded()) {
                stopPromise.complete();
            } else {
                stopPromise.fail(ar.cause());
            }
        });
    }

    private void updateActiveConnectionsAfterStop() {
        try {
            runtimeRegistry.markStatus(tunnel, TunnelStatus.STOPPED);
        } catch (Exception ignore) {
        }
    }

    private Future<Void> stopOpenedServers() {
        List<Future<Void>> futures = new ArrayList<>();
        if (publicServer != null) {
            futures.add(publicServer.close());
            publicServer = null;
        }
        if (agentServer != null) {
            futures.add(agentServer.close());
            agentServer = null;
        }
        if (futures.isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.all(futures).mapEmpty();
    }

    private static class AgentSession {
        private final String sessionId;
        private final ServerWebSocket webSocket;
        private String agentName;
        private boolean authenticated;
        private boolean active;
        private Instant connectedAt;
        private Instant lastSeenAt;
        private long authTimerId;
        private long heartbeatTimerId;

        AgentSession(String sessionId, ServerWebSocket webSocket) {
            this.sessionId = sessionId;
            this.webSocket = webSocket;
        }
    }

    private static class RequestContext {
        private final String requestId;
        private final String sessionId;
        private final NetSocket socket;
        private long timeoutId;
        private boolean closed;

        RequestContext(String requestId, String sessionId, NetSocket socket) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.socket = socket;
        }
    }
}
