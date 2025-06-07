package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.FrpAgentConfig;
import net.ximatai.frp.mock.MockServerVerticle;
import net.ximatai.frp.server.config.FrpServerConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrpTest {

    private int mockServerPort = 7788;
    @Inject
    Vertx vertx;

    @Inject
    FrpServerConfig serverConfig;

    @Inject
    FrpAgentConfig agentConfig;

    @BeforeAll
    void beforeAll() {
        Assertions.assertNotNull(serverConfig);
        Assertions.assertNotNull(agentConfig);

        vertx.deployVerticle(new MockServerVerticle(mockServerPort))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    @Test
    void testMockServer() throws Exception {
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
                .statusCode(200);
//                .body(is("hello frp"));
    }

}
