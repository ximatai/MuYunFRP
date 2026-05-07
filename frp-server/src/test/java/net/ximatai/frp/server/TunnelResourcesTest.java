package net.ximatai.frp.server;

import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.json.JsonObject;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import net.ximatai.frp.common.MessageUtil;
import net.ximatai.frp.common.OperationType;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.service.TunnelManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(TunnelResourcesTest.Profile.class)
class TunnelResourcesTest {
    private static final String STORE_PATH = "build/test-tunnels/tunnel-resources-test-" + System.nanoTime() + ".json";

    @Inject
    Vertx vertx;

    @Test
    void shouldRejectMissingBasicAuth() {
        given()
                .when()
                .get("/api/tunnels")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Basic realm=\"MuYunFRP\"");
    }

    @Test
    void shouldRejectWrongBasicAuth() {
        given()
                .auth().preemptive().basic("admin", "wrong-password")
                .when()
                .get("/api/tunnels")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldReturnTunnelRuntime() {
        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .get("/api/tunnels")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void shouldCreateAndGetTunnel() throws Exception {
        String agentToken = given()
                .auth().preemptive().basic("admin", "password")
                .contentType("application/json")
                .body(new TunnelManager.CreateTunnelRequest("api_create", ProxyType.tcp, 19682, 19683))
                .when()
                .post("/api/tunnels")
                .then()
                .statusCode(201)
                .body("name", is("api_create"))
                .body("agentToken", notNullValue())
                .extract()
                .path("agentToken");

        Assertions.assertFalse(Files.readString(Path.of(STORE_PATH)).contains(agentToken));

        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .get("/api/tunnels/api_create")
                .then()
                .statusCode(200)
                .body("name", is("api_create"))
                .body("", not(hasKey("tokenHash")));
    }

    @Test
    void shouldInvalidateOldAgentTokenAfterReset() throws Exception {
        String name = "api_reset_auth";
        int agentPort = 19883;
        String oldToken = given()
                .auth().preemptive().basic("admin", "password")
                .contentType("application/json")
                .body(new TunnelManager.CreateTunnelRequest(name, ProxyType.tcp, 19882, agentPort))
                .when()
                .post("/api/tunnels")
                .then()
                .statusCode(201)
                .extract()
                .path("agentToken");

        Assertions.assertEquals(OperationType.AUTH_OK, authenticateAgent(agentPort, oldToken, "old-agent"));

        String newToken = given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .post("/api/tunnels/%s/token/reset".formatted(name))
                .then()
                .statusCode(200)
                .body("agentToken", notNullValue())
                .extract()
                .path("agentToken");

        Assertions.assertNotEquals(oldToken, newToken);
        Assertions.assertEquals(OperationType.AUTH_FAIL, authenticateAgent(agentPort, oldToken, "old-agent"));
        Assertions.assertEquals(OperationType.AUTH_OK, authenticateAgent(agentPort, newToken, "new-agent"));
        Assertions.assertFalse(Files.readString(Path.of(STORE_PATH)).contains(newToken));
    }

    @Test
    void shouldRestartResetTokenAndDeleteTunnel() {
        String name = "api_lifecycle";
        given()
                .auth().preemptive().basic("admin", "password")
                .contentType("application/json")
                .body(new TunnelManager.CreateTunnelRequest(name, ProxyType.tcp, 19782, 19783))
                .when()
                .post("/api/tunnels")
                .then()
                .statusCode(201)
                .body("agentToken", notNullValue());

        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .post("/api/tunnels/%s/restart".formatted(name))
                .then()
                .statusCode(200)
                .body("name", is(name))
                .body("status", is("LISTENING"));

        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .post("/api/tunnels/%s/token/reset".formatted(name))
                .then()
                .statusCode(200)
                .body("name", is(name))
                .body("agentToken", notNullValue())
                .body("", not(hasKey("tokenHash")));

        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .delete("/api/tunnels/%s".formatted(name))
                .then()
                .statusCode(204);

        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .get("/api/tunnels/%s".formatted(name))
                .then()
                .statusCode(404)
                .body("error", is("TUNNEL_NOT_FOUND"));
    }

    @Test
    void shouldNotExposeOldSingularApi() {
        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .get("/api/tunnel")
                .then()
                .statusCode(404);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("frp-server.management.port", "18089"),
                    Map.entry("frp-server.management.username", "admin"),
                    Map.entry("frp-server.management.password", "password"),
                    Map.entry("frp-server.tunnel-store.path", STORE_PATH),
                    Map.entry("quarkus.http.port", "0")
            );
        }
    }

    private OperationType authenticateAgent(int agentPort, String token, String agentName) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<OperationType> operation = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        vertx.createWebSocketClient(new WebSocketClientOptions()
                        .setDefaultHost("127.0.0.1")
                        .setDefaultPort(agentPort))
                .connect("/")
                .onSuccess(ws -> {
                    ws.frameHandler(frame -> {
                        if (!frame.isBinary()) {
                            return;
                        }
                        OperationType type = MessageUtil.getOperationType(frame.binaryData());
                        if (type == OperationType.AUTH_OK || type == OperationType.AUTH_FAIL) {
                            operation.set(type);
                            ws.close();
                            latch.countDown();
                        }
                    });
                    ws.closeHandler(v -> latch.countDown());
                    ws.writeBinaryMessage(MessageUtil.buildControlMessage(
                            OperationType.AUTH,
                            new JsonObject()
                                    .put("version", 1)
                                    .put("token", token)
                                    .put("agentName", agentName)
                    ));
                })
                .onFailure(error -> {
                    failure.set(error);
                    latch.countDown();
                });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return operation.get();
    }
}
