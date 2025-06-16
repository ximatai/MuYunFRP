package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.mock.MockWebServerVerticle;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelLinkerVerticle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebFrpTest {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private static final int mockServerPort = 7788;
    private static final int frpTunnelAgentPort = 8083;
    private static final int frpTunnelOpenPort = 8082;

    static {
        RestAssured.config = RestAssured.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 1)
                        .setParam("http.socket.timeout", 3)
                );
    }

    @Inject
    Vertx vertx;

    @BeforeAll
    void beforeAll() {

        Assertions.assertFalse(Boolean.getBoolean("vertx.disableWebsockets"));

        vertx.deployVerticle(new MockWebServerVerticle(mockServerPort))
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
                return ProxyType.http;
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
    void testMockServer() {
        testWithPort(mockServerPort);
    }

    @Test
    void testFrpServer() {
        testWithPort(frpTunnelOpenPort);
    }

    private void testWithPort(int port) {
        given()
                .when()
                .get("http://localhost:%s/test".formatted(port))
                .then()
                .statusCode(200)
                .body(is("hello"));

        System.out.println("===");

        given()
                .contentType("application/json")
                .body(Map.of("name", "frp"))
                .when()
                .post("http://localhost:%s/test".formatted(port))
                .then()
                .statusCode(200)
                .body(is("hello frp"));

        System.out.println("===");
    }

}
