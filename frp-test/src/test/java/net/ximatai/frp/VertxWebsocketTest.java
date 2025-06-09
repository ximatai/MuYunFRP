package net.ximatai.frp;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class VertxWebsocketTest {

    Vertx vertx = Vertx.vertx();

    private int port;

    @BeforeAll
    void beforeAll() {
        startServer();
    }

    @Test
    void testRequest(VertxTestContext testContext) {

        WebSocketClientOptions options = new WebSocketClientOptions()
                .setDefaultHost("127.0.0.1")
                .setDefaultPort(port);

        WebSocketClient client = vertx.createWebSocketClient(options);

        client.connect("/")
                .onSuccess(ws -> {
                    testContext.completeNow();
                    System.out.println("Connected!");
                })
                .onFailure(testContext::failNow);
    }

    void startServer() {
        HttpServer server = vertx.createHttpServer()
                .webSocketHandler(webSocket -> {
                    System.out.println("Connected!");
                })
                .listen(9527)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        port = server.actualPort();
    }

}
