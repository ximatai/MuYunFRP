package net.ximatai.frp.mock;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;

public class MockTcpServerVerticle extends AbstractVerticle {

    private final int port;

    public MockTcpServerVerticle(int port) {
        this.port = port;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        vertx.createNetServer()
                .connectHandler(socket -> {
                    socket.handler(buffer -> {
                        socket.write(buffer);
                    });
                })
                .listen(port)
                .compose(server -> Future.<Void>succeededFuture())
                .onComplete(startPromise);

    }
}
