package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.Auth;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.mock.MockTcpServerVerticle;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelLinkerVerticle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpFrpTest {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private int mockServerPort;
    private int frpTunnelAgentPort;
    private int frpTunnelOpenPort;

    @Inject
    Vertx vertx;

    @BeforeAll
    void beforeAll() {
        mockServerPort = availablePort();
        frpTunnelAgentPort = availablePort();
        frpTunnelOpenPort = availablePort();

        vertx.deployVerticle(new MockTcpServerVerticle(mockServerPort))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        LOGGER.info("mockServer deploy success.");

        Tunnel testTunnel = Tunnel.createRecord("测试", ProxyType.http, frpTunnelOpenPort, frpTunnelAgentPort);

        TunnelLinkerVerticle tunnelLinkerVerticle = new TunnelLinkerVerticle(vertx, testTunnel);

        vertx.deployVerticle(tunnelLinkerVerticle).toCompletionStage().toCompletableFuture().join();

        LOGGER.info("TunnelLinker success.");

        Agent testAgent = new Agent() {
            @Override
            public ProxyType type() {
                return ProxyType.tcp;
            }

            @Override
            public String agentName() {
                return "tcp-test-agent";
            }

            @Override
            public FrpTunnel frpTunnel() {
                return new FrpTunnel() {
                    @Override
                    public String host() {
                        return "127.0.0.1";
                    }

                    @Override
                    public int port() {
                        return frpTunnelAgentPort;
                    }
                };
            }

            @Override
            public Auth auth() {
                return () -> "test-token";
            }

            @Override
            public ProxyServer proxy() {
                return new ProxyServer() {
                    @Override
                    public String host() {
                        return "127.0.0.1";
                    }

                    @Override
                    public int port() {
                        return mockServerPort;
                    }
                };
            }
        };

        AgentLinkerVerticle agentLinkerVerticle = new AgentLinkerVerticle(testAgent);

        vertx.deployVerticle(agentLinkerVerticle).toCompletionStage().toCompletableFuture().join();

        LOGGER.info("AgentLinker success.");

    }

    @Test
    void testMockServer() throws InterruptedException {
        testWithPort(mockServerPort);
    }

    @Test
    void testFrpServer() throws InterruptedException {
        testWithPort(frpTunnelOpenPort);
    }

    @Test
    void preservesMultipartTcpByteOrderWhileTargetConnects() throws InterruptedException {
        VertxTestContext testContext = new VertxTestContext();
        String[] parts = {"00|", "01|", "02|", "03|", "04|", "05|", "06|", "07|"};
        String expected = String.join("", parts);
        AtomicReference<StringBuilder> received = new AtomicReference<>(new StringBuilder());

        vertx.createNetClient()
                .connect(frpTunnelOpenPort, "127.0.0.1")
                .onSuccess(socket -> {
                    socket.handler(buffer -> {
                        StringBuilder response = received.get();
                        response.append(buffer.toString());
                        if (response.length() >= expected.length()) {
                            testContext.verify(() -> Assertions.assertEquals(expected, response.toString()));
                            testContext.completeNow();
                        }
                    });
                    for (String part : parts) {
                        socket.write(part);
                    }
                })
                .onFailure(testContext::failNow);

        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }
    }

    @Test
    void keepsMultipartStreamsIsolatedAcrossConnections() throws InterruptedException {
        VertxTestContext testContext = new VertxTestContext();
        AtomicInteger complete = new AtomicInteger();
        verifyMultipartEcho(testContext, complete, "a0|a1|a2|a3|");
        verifyMultipartEcho(testContext, complete, "b0|b1|b2|b3|");

        Assertions.assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }
    }

    private void testWithPort(int port) throws InterruptedException {
        VertxTestContext testContext = new VertxTestContext();

        String text = "hello world!";

        vertx.createNetClient()
                .connect(port, "127.0.0.1")
                .onSuccess(socket -> {
                    socket.write(text);

                    socket.handler(buffer -> {
                        testContext.verify(() -> {
                            Assertions.assertEquals(text, buffer.toString());
                            testContext.completeNow();
                        });
                    });
                })
                .onFailure(testContext::failNow);

        testContext.awaitCompletion(10, TimeUnit.SECONDS);

        if (testContext.failed()) {
            throw new AssertionError(testContext.causeOfFailure());
        }
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate test port", ex);
        }
    }

    private void verifyMultipartEcho(VertxTestContext testContext, AtomicInteger complete, String expected) {
        AtomicReference<StringBuilder> received = new AtomicReference<>(new StringBuilder());
        vertx.createNetClient()
                .connect(frpTunnelOpenPort, "127.0.0.1")
                .onSuccess(socket -> {
                    socket.handler(buffer -> {
                        StringBuilder response = received.get();
                        response.append(buffer.toString());
                        if (response.length() >= expected.length()) {
                            testContext.verify(() -> Assertions.assertEquals(expected, response.toString()));
                            if (complete.incrementAndGet() == 2) {
                                testContext.completeNow();
                            }
                        }
                    });
                    for (int offset = 0; offset < expected.length(); offset += 3) {
                        socket.write(expected.substring(offset, offset + 3));
                    }
                })
                .onFailure(testContext::failNow);
    }

}
