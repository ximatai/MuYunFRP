package net.ximatai.frp;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class VertxHttp2Test {

    Vertx vertx = Vertx.vertx();

    private int port;

    @BeforeAll
    void beforeAll() {
        startServer();
    }

    @Test
    void testRequest(VertxTestContext testContext) {

        // 配置 HTTP/2 客户端选项
        HttpClientOptions options = new HttpClientOptions()
                .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2)
//                .setSsl(true)
                .setUseAlpn(true)
                .setTrustAll(true); // 仅用于测试，生产环境不要使用

        HttpClient client = vertx.createHttpClient(options);

        // 发送 HTTP/2 请求
        client.request(HttpMethod.GET, port, "localhost", "/")
                .compose(request -> {
                    request.end();
                    return request.response();
                })
                .onSuccess(response -> {
                    Assertions.assertEquals(200, response.statusCode());
                    Assertions.assertEquals(HttpVersion.HTTP_2, response.version());
                    testContext.completeNow();
                })
                .onFailure(err -> {
                    System.err.println("Something went wrong: " + err.getMessage());
                    testContext.failNow(err);
                });

    }

    void startServer() {
        HttpServerOptions options = new HttpServerOptions()
                .setUseAlpn(true);

        HttpServer server = vertx.createHttpServer(options)
                .requestHandler(request -> {
                    request.response()
                            .putHeader("content-type", "text/plain")
                            .end("Hello from HTTP/2 server!");
                })
                .listen(9527)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        port = server.actualPort();
    }

}
