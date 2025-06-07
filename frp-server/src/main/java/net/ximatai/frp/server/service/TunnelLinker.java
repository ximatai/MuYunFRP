package net.ximatai.frp.server.service;

import io.vertx.core.Vertx;
import net.ximatai.frp.server.config.Tunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TunnelLinker {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private final Vertx vertx;

    public TunnelLinker(Vertx vertx) {
        this.vertx = vertx;
    }

    public void link(Tunnel tunnel) {
        int userPort = tunnel.openPort();
        int agentPort = tunnel.agentPort();

        LOGGER.info("Try To Link {},UserPort is {},AgentPort is {}", tunnel.name(), userPort, agentPort);

        vertx.createNetServer()
                .connectHandler(socket -> {

                })
                .listen(userPort)
                .onFailure(throwable -> {
                    LOGGER.error("Link {} Failed With UserPort {}", tunnel.name(), userPort, throwable);
                });

        vertx.createHttpServer()
                .requestHandler(request -> {
                    request.response().end();
                })
                .listen(agentPort)
                .onFailure(throwable -> {
                    LOGGER.error("Link {} Failed With AgentPort {}", tunnel.name(), agentPort, throwable);
                });

    }

}
