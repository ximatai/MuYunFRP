package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.config.ProxyType;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.mock.MockServerVerticle;
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
class FrpTest {
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

        vertx.deployVerticle(new MockServerVerticle(mockServerPort))
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
            public String name() {
                return "test";
            }

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

        AgentLinkerVerticle agentLinkerVerticle = new AgentLinkerVerticle(vertx, testAgent);

        vertx.deployVerticle(agentLinkerVerticle).toCompletionStage().toCompletableFuture().join();

        LOGGER.info("AgentLinker success.");

    }

    @Test
    void testMockServer() {
        given()
                .when()
                .get("http://localhost:%s/test".formatted(mockServerPort))
                .then()
                .statusCode(200)
                .body(is("hello"));

        given()
                .contentType("application/json")
                .body(Map.of("name", "frp"))
                .when()
                .post("http://localhost:%s/test".formatted(mockServerPort))
                .then()
                .statusCode(200)
                .body(is("hello frp"));
    }

    @Test
    void testFrpServer() {
        given()
                .when()
                .get("http://localhost:%s/test".formatted(frpTunnelOpenPort))
                .then()
                .statusCode(200)
                .body(is("hello"));

        System.out.println("===");

        given()
                .contentType("application/json")
                .body(Map.of("name", "frp"))
                .when()
                .post("http://localhost:%s/test".formatted(frpTunnelOpenPort))
                .then()
                .statusCode(200)
                .body(is("hello frp"));

        System.out.println("===");
    }

}
