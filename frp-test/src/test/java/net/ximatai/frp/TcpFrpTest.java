package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.Auth;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelLinkerVerticle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            TestPorts ports = randomPorts();
            NetServer candidateMockServer = null;
            String tunnelDeployment = null;
            try {
                candidateMockServer = vertx.createNetServer()
                        .connectHandler(socket -> socket.handler(socket::write))
                        .listen(ports.mockServerPort, "127.0.0.1")
                        .toCompletionStage().toCompletableFuture().join();

                Tunnel testTunnel = Tunnel.createRecord("测试", ProxyType.http, ports.openPort, ports.agentPort);
                tunnelDeployment = vertx.deployVerticle(new TunnelLinkerVerticle(vertx, testTunnel))
                        .toCompletionStage().toCompletableFuture().join();

                Agent testAgent = testAgent(ports);
                vertx.deployVerticle(new AgentLinkerVerticle(testAgent))
                        .toCompletionStage().toCompletableFuture().join();

                mockServerPort = ports.mockServerPort;
                frpTunnelAgentPort = ports.agentPort;
                frpTunnelOpenPort = ports.openPort;
                LOGGER.info("TCP FRP test fixture started on ports mock={}, open={}, agent={}",
                        mockServerPort, frpTunnelOpenPort, frpTunnelAgentPort);
                return;
            } catch (Exception failure) {
                lastFailure = failure;
                if (tunnelDeployment != null) {
                    vertx.undeploy(tunnelDeployment).toCompletionStage().toCompletableFuture().join();
                }
                if (candidateMockServer != null) {
                    candidateMockServer.close().toCompletionStage().toCompletableFuture().join();
                }
            }
        }
        throw new IllegalStateException("Unable to start TCP FRP test fixture after 20 attempts", lastFailure);
    }

    private Agent testAgent(TestPorts ports) {
        return new Agent() {
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
                        return ports.agentPort;
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
                        return ports.mockServerPort;
                    }
                };
            }
        };
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

    private static TestPorts randomPorts() {
        Set<Integer> ports = new HashSet<>();
        while (ports.size() < 3) {
            ports.add(ThreadLocalRandom.current().nextInt(20_000, 60_000));
        }
        int[] values = ports.stream().mapToInt(Integer::intValue).toArray();
        return new TestPorts(values[0], values[1], values[2]);
    }

    private record TestPorts(int mockServerPort, int agentPort, int openPort) { }

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
