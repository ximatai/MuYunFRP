package net.ximatai.frp.mock;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockServerVerticle extends AbstractVerticle {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private final int port;

    public MockServerVerticle(int port) {
        this.port = port;
    }

    @Override
    public void start() throws Exception {

        var router = Router.router(Vertx.vertx());

        router.get("/test")
                .handler(ctx -> {
                    ctx.response().end("hello");
                });

        router.post("/test")
                .handler(BodyHandler.create())
                .handler(ctx -> {
                    var body = ctx.body().asJsonObject();
                    ctx.response().end("hello %s".formatted(body.getString("name", "world")));
                });

        vertx.createHttpServer().requestHandler(router)
                .listen(port)
                .onSuccess(server -> {
                    LOGGER.info("MockServerVerticle started on port {}", port);
                })
                .onFailure(err -> {
                    LOGGER.error("MockServerVerticle start failed", err);
                });
    }
}
