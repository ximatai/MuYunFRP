package net.ximatai.frp;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.common.MessageUtil;
import net.ximatai.frp.common.OperationType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(VertxExtension.class)
class AgentRequestLifecycleTest {

    @Test
    void boundsDataQueuedBeforeTargetConnect(Vertx vertx, VertxTestContext testContext) throws Exception {
        var upstream = vertx.createNetServer().connectHandler(socket -> socket.handler(ignored -> { }))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        AtomicInteger closeFrames = new AtomicInteger();
        AgentLinkerVerticle linker = linker(vertx, upstream.actualPort(), closeFrames);
        String requestId = UUID.randomUUID().toString();

        vertx.getOrCreateContext().runOnContext(ignored -> {
            invokeConnect(linker, controlSocket(linker), requestId);
            for (int i = 0; i < 17; i++) {
                invoke(linker, "handleDataRequest", new Class[]{String.class, Buffer.class}, requestId, Buffer.buffer(new byte[65535]));
            }
            testContext.verify(() -> {
                Assertions.assertEquals(1, closeFrames.get(), "overflow must close the request once");
                Assertions.assertTrue(requests(linker).isEmpty(), "overflow must release request state");
            });
            vertx.setTimer(100, timerId -> {
                linker.stop();
                upstream.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
            });
        });

        await(testContext);
    }

    @Test
    void closesAndReleasesRequestWhenTargetConnectionFails(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger closeFrames = new AtomicInteger();
        AgentLinkerVerticle linker = linker(vertx, unusedPort(), closeFrames);
        String requestId = UUID.randomUUID().toString();

        vertx.getOrCreateContext().runOnContext(ignored ->
                invokeConnect(linker, controlSocket(linker), requestId));

        vertx.setTimer(2000, ignored -> testContext.failNow("target connection failure did not close the request"));
        vertx.setPeriodic(10, timerId -> {
            if (closeFrames.get() == 1) {
                vertx.cancelTimer(timerId);
                testContext.verify(() -> Assertions.assertTrue(requests(linker).isEmpty()));
                linker.stop();
                testContext.completeNow();
            }
        });

        await(testContext);
    }

    @Test
    void closeBeforeTargetConnectDoesNotResurrectTheRequest(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger targetBytes = new AtomicInteger();
        var upstream = vertx.createNetServer().connectHandler(socket ->
                        socket.handler(buffer -> targetBytes.addAndGet(buffer.length())))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        AgentLinkerVerticle linker = linker(vertx, upstream.actualPort(), new AtomicInteger());
        String requestId = UUID.randomUUID().toString();

        vertx.getOrCreateContext().runOnContext(ignored -> {
            invokeConnect(linker, controlSocket(linker), requestId);
            invoke(linker, "handleDataRequest", new Class[]{String.class, Buffer.class}, requestId, Buffer.buffer("discard"));
            invoke(linker, "handleCloseRequest", new Class[]{String.class}, requestId);
            vertx.setTimer(100, timerId -> {
                testContext.verify(() -> {
                    Assertions.assertTrue(requests(linker).isEmpty());
                    Assertions.assertEquals(0, targetBytes.get());
                });
                linker.stop();
                upstream.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
            });
        });

        await(testContext);
    }

    @Test
    void preservesFifoWhenDataArrivesBeforeTargetConnectCompletes(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger closeFrames = new AtomicInteger();
        Buffer received = Buffer.buffer();
        AtomicReference<AgentLinkerVerticle> linker = new AtomicReference<>();
        AtomicReference<NetServer> upstreamRef = new AtomicReference<>();
        var upstream = vertx.createNetServer().connectHandler(socket -> socket.handler(buffer -> {
                    received.appendBuffer(buffer);
                    if (received.length() == 32) {
                        testContext.verify(() -> {
                            for (int i = 0; i < 8; i++) {
                                Assertions.assertEquals(i, received.getInt(i * 4));
                            }
                        });
                        linker.get().stop();
                        upstreamRef.get().close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
                    }
                }))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        upstreamRef.set(upstream);
        linker.set(linker(vertx, upstream.actualPort(), closeFrames));
        String requestId = UUID.randomUUID().toString();

        vertx.getOrCreateContext().runOnContext(ignored -> {
            invokeConnect(linker.get(), controlSocket(linker.get()), requestId);
            for (int i = 0; i < 8; i++) {
                invoke(linker.get(), "handleDataRequest", new Class[]{String.class, Buffer.class}, requestId, Buffer.buffer().appendInt(i));
            }
        });

        await(testContext);
    }

    @Test
    void doesNotResurrectARequestAfterControlSessionReplacement(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger newSessionWrites = new AtomicInteger();
        var upstream = vertx.createNetServer().connectHandler(socket -> socket.handler(ignored -> { }))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        AgentLinkerVerticle linker = linker(vertx, upstream.actualPort(), new AtomicInteger());
        WebSocket oldSession = controlSocket(linker);
        WebSocket replacement = controlSocket(newSessionWrites);
        String requestId = UUID.randomUUID().toString();

        vertx.getOrCreateContext().runOnContext(ignored -> {
            invokeConnect(linker, oldSession, requestId);
            invoke(linker, "setControlSocket", new Class[]{WebSocket.class}, replacement);
            vertx.setTimer(100, timerId -> {
                testContext.verify(() -> {
                    Assertions.assertTrue(requests(linker).isEmpty());
                    Assertions.assertEquals(0, newSessionWrites.get());
                });
                linker.stop();
                upstream.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
            });
        });

        await(testContext);
    }

    @Test
    void closesAndNotifiesWhenAnAsyncTargetWriteFails(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger closeFrames = new AtomicInteger();
        AgentLinkerVerticle linker = linker(vertx, 0, closeFrames);
        String requestId = UUID.randomUUID().toString();
        Object request = request(linker, requestId, controlSocket(linker));
        requests(linker).put(requestId, request);
        setRequestField(request, "targetSocket", failingTargetSocket());

        vertx.getOrCreateContext().runOnContext(ignored -> {
            invoke(linker, "handleDataRequest", new Class[]{String.class, Buffer.class}, requestId, Buffer.buffer("payload"));
            testContext.verify(() -> {
                Assertions.assertEquals(1, closeFrames.get());
                Assertions.assertTrue(requests(linker).isEmpty());
            });
            linker.stop();
            testContext.completeNow();
        });

        await(testContext);
    }

    private AgentLinkerVerticle linker(Vertx vertx, int targetPort, AtomicInteger closeFrames) throws Exception {
        ProxyServer proxyServer = (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ProxyServer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "host" -> "127.0.0.1";
                    case "port" -> targetPort;
                    default -> null;
                });
        Agent agent = (Agent) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Agent.class},
                (proxy, method, args) -> method.getName().equals("proxy") ? proxyServer : null);
        AgentLinkerVerticle linker = new AgentLinkerVerticle(agent);
        linker.init(vertx, vertx.getOrCreateContext());
        setField(linker, "controlSocket", controlSocket(closeFrames));
        setField(linker, "authenticated", true);
        return linker;
    }

    private WebSocket controlSocket(AtomicInteger closeFrames) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("isClosed")) {
                return false;
            }
            if (method.getName().equals("writeBinaryMessage")) {
                Buffer frame = (Buffer) args[0];
                if (MessageUtil.getOperationType(frame) == OperationType.CLOSE) {
                    closeFrames.incrementAndGet();
                }
                return Future.succeededFuture();
            }
            if (method.getName().equals("toString")) {
                return "test-control-socket";
            }
            return null;
        };
        return (WebSocket) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{WebSocket.class}, handler);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requests(AgentLinkerVerticle linker) {
        try {
            Field field = AgentLinkerVerticle.class.getDeclaredField("pendingRequests");
            field.setAccessible(true);
            return (Map<String, Object>) field.get(linker);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void setField(AgentLinkerVerticle linker, String name, Object value) throws Exception {
        Field field = AgentLinkerVerticle.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(linker, value);
    }

    private WebSocket controlSocket(AgentLinkerVerticle linker) {
        try {
            Field field = AgentLinkerVerticle.class.getDeclaredField("controlSocket");
            field.setAccessible(true);
            return (WebSocket) field.get(linker);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void invokeConnect(AgentLinkerVerticle linker, WebSocket sourceSocket, String requestId) {
        invoke(linker, "handleConnectRequest", new Class[]{WebSocket.class, String.class}, sourceSocket, requestId);
    }

    private Object request(AgentLinkerVerticle linker, String requestId, WebSocket sourceSocket) {
        try {
            Class<?> type = Class.forName(AgentLinkerVerticle.class.getName() + "$RequestConnection");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, WebSocket.class);
            constructor.setAccessible(true);
            return constructor.newInstance(requestId, sourceSocket);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void setRequestField(Object request, String name, Object value) {
        try {
            Field field = request.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(request, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private NetSocket failingTargetSocket() {
        return (NetSocket) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{NetSocket.class}, (proxy, method, args) -> {
            if (method.getName().equals("write")) {
                return Future.failedFuture("target write failure");
            }
            if (method.getName().equals("close")) {
                return Future.succeededFuture();
            }
            return null;
        });
    }

    private void invoke(AgentLinkerVerticle linker, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = AgentLinkerVerticle.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(linker, arguments);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void await(VertxTestContext testContext) throws InterruptedException {
        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }
    }
}
