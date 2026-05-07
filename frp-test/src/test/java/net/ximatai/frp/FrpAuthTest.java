package net.ximatai.frp;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import net.ximatai.frp.common.MessageUtil;
import net.ximatai.frp.common.OperationType;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelLinkerVerticle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ExtendWith(VertxExtension.class)
class FrpAuthTest {

    @Test
    void shouldRejectAgentWithWrongToken(Vertx vertx, VertxTestContext testContext) {
        int openPort = 19082;
        int agentPort = 19083;
        Tunnel tunnel = Tunnel.createRecord("auth-fail-test", ProxyType.tcp, openPort, agentPort, "right-token");

        vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                .compose(id -> vertx.createWebSocketClient(new WebSocketClientOptions()
                                .setDefaultHost("127.0.0.1")
                                .setDefaultPort(agentPort))
                        .connect("/"))
                .onSuccess(ws -> {
                    ws.frameHandler(frame -> {
                        if (frame.isBinary()
                                && MessageUtil.getOperationType(frame.binaryData()) == OperationType.AUTH_FAIL) {
                            testContext.completeNow();
                        }
                    });
                    ws.closeHandler(v -> testContext.completeNow());
                    ws.writeBinaryMessage(MessageUtil.buildControlMessage(
                            OperationType.AUTH,
                            new JsonObject()
                                    .put("version", 1)
                                    .put("token", "wrong-token")
                                    .put("agentName", "bad-agent")
                    ));
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void shouldCloseUserConnectionWhenNoAgentOnline(Vertx vertx, VertxTestContext testContext) {
        int openPort = 19182;
        int agentPort = 19183;
        Tunnel tunnel = Tunnel.createRecord("no-agent-test", ProxyType.tcp, openPort, agentPort, "token");

        vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                .compose(id -> vertx.createNetClient().connect(openPort, "127.0.0.1"))
                .onSuccess(socket -> {
                    socket.closeHandler(v -> testContext.completeNow());
                    socket.write(Buffer.buffer("hello-" + UUID.randomUUID()));
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void shouldIgnoreTransferFrameBeforeAuth(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        int openPort = 19282;
        int agentPort = 19283;
        Tunnel tunnel = Tunnel.createRecord("unauth-frame-test", ProxyType.tcp, openPort, agentPort, "token");

        vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                .compose(id -> vertx.createWebSocketClient(new WebSocketClientOptions()
                                .setDefaultHost("127.0.0.1")
                                .setDefaultPort(agentPort))
                        .connect("/"))
                .onSuccess(ws -> {
                    ws.writeBinaryMessage(MessageUtil.buildDataMessage(UUID.randomUUID().toString(), Buffer.buffer("bad")));
                    vertx.setTimer(100, ignored -> vertx.createNetClient()
                            .connect(openPort, "127.0.0.1")
                            .onSuccess(socket -> {
                                socket.closeHandler(v -> testContext.completeNow());
                                socket.write("hello");
                            })
                            .onFailure(testContext::failNow));
                })
                .onFailure(testContext::failNow);

        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }
    }

    @Test
    void shouldRejectMalformedAuthPayload(Vertx vertx, VertxTestContext testContext) {
        int openPort = 19382;
        int agentPort = 19383;
        Tunnel tunnel = Tunnel.createRecord("malformed-auth-test", ProxyType.tcp, openPort, agentPort, "token");

        vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                .compose(id -> vertx.createWebSocketClient(new WebSocketClientOptions()
                                .setDefaultHost("127.0.0.1")
                                .setDefaultPort(agentPort))
                        .connect("/"))
                .onSuccess(ws -> {
                    ws.frameHandler(frame -> {
                        if (frame.isBinary()
                                && MessageUtil.getOperationType(frame.binaryData()) == OperationType.AUTH_FAIL) {
                            testContext.completeNow();
                        }
                    });
                    ws.closeHandler(v -> testContext.completeNow());
                    ws.writeBinaryMessage(Buffer.buffer().appendByte(OperationType.AUTH.getValue()).appendString("{bad-json"));
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void shouldReplaceExistingAgentSession(Vertx vertx, VertxTestContext testContext) {
        int openPort = 19482;
        int agentPort = 19483;
        Tunnel tunnel = Tunnel.createRecord("replace-agent-test", ProxyType.tcp, openPort, agentPort, "token");
        WebSocketClientOptions options = new WebSocketClientOptions()
                .setDefaultHost("127.0.0.1")
                .setDefaultPort(agentPort);

        vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                .compose(id -> vertx.createWebSocketClient(options).connect("/"))
                .onSuccess(first -> {
                    first.frameHandler(frame -> {
                        if (frame.isBinary()
                                && MessageUtil.getOperationType(frame.binaryData()) == OperationType.AUTH_OK) {
                            connectReplacementAgent(vertx, options, first, testContext);
                        }
                    });
                    writeAuth(first, "first-agent");
                })
                .onFailure(testContext::failNow);
    }

    private void connectReplacementAgent(
            Vertx vertx,
            WebSocketClientOptions options,
            WebSocket first,
            VertxTestContext testContext
    ) {
        vertx.createWebSocketClient(options)
                .connect("/")
                .onSuccess(second -> {
                    first.closeHandler(v -> testContext.completeNow());
                    second.frameHandler(frame -> {
                        if (frame.isBinary()
                                && MessageUtil.getOperationType(frame.binaryData()) == OperationType.AUTH_OK) {
                            vertx.setTimer(1000, ignored -> testContext.failNow(new AssertionError("first agent was not replaced")));
                        }
                    });
                    writeAuth(second, "second-agent");
                })
                .onFailure(testContext::failNow);
    }

    private void writeAuth(WebSocket ws, String agentName) {
        ws.writeBinaryMessage(MessageUtil.buildControlMessage(
                OperationType.AUTH,
                new JsonObject()
                        .put("version", 1)
                        .put("token", "token")
                        .put("agentName", agentName)
        ));
    }
}
