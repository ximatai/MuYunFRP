package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.config.ProxyType;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.mock.MockTcpServerVerticle;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelLinkerVerticle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpFrpTest {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private static final int mockServerPort = 17788;
    private static final int frpTunnelAgentPort = 18083;
    private static final int frpTunnelOpenPort = 18082;

    @Inject
    Vertx vertx;

    @BeforeAll
    void beforeAll() {

        vertx.deployVerticle(new MockTcpServerVerticle(mockServerPort))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        LOGGER.info("mockServer deploy success.");

        Tunnel testTunnel = new Tunnel() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public int openPort() {
                return frpTunnelOpenPort;
            }

            @Override
            public int agentPort() {
                return frpTunnelAgentPort;
            }
        };

        TunnelLinkerVerticle tunnelLinkerVerticle = new TunnelLinkerVerticle(vertx, testTunnel);

        vertx.deployVerticle(tunnelLinkerVerticle).toCompletionStage().toCompletableFuture().join();

        LOGGER.info("TunnelLinker success.");

        Agent testAgent = new Agent() {
            @Override
            public ProxyType type() {
                return ProxyType.tcp;
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

}
