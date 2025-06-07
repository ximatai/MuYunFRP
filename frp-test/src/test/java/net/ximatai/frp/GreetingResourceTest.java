package net.ximatai.frp;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import net.ximatai.frp.mock.MockServerVerticle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GreetingResourceTest {

    @Inject
    Vertx vertx;

    private int mockServerPort = 7788;

    @BeforeAll
    void beforeAll() {
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
