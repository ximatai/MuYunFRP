package net.ximatai.frp.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
@TestProfile(TunnelResourcesTest.Profile.class)
class TunnelResourcesTest {

    @Test
    void shouldRejectMissingBasicAuth() {
        given()
                .when()
                .get("/api/tunnel")
                .then()
                .statusCode(401)
                .header("WWW-Authenticate", "Basic realm=\"MuYunFRP\"");
    }

    @Test
    void shouldRejectWrongBasicAuth() {
        given()
                .auth().preemptive().basic("admin", "wrong-password")
                .when()
                .get("/api/tunnel")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldReturnTunnelRuntime() {
        given()
                .auth().preemptive().basic("admin", "password")
                .when()
                .get("/api/tunnel")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].name", is("api-test"))
                .body("[0].tokenConfigured", is(true));
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("frp-server.management.port", "18089"),
                    Map.entry("frp-server.management.username", "admin"),
                    Map.entry("frp-server.management.password", "password"),
                    Map.entry("frp-server.tunnels[0].name", "api-test"),
                    Map.entry("frp-server.tunnels[0].type", "tcp"),
                    Map.entry("frp-server.tunnels[0].open-port", "19582"),
                    Map.entry("frp-server.tunnels[0].agent-port", "19583"),
                    Map.entry("frp-server.tunnels[0].token", "tunnel-token"),
                    Map.entry("quarkus.http.port", "0")
            );
        }
    }
}
