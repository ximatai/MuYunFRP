package net.ximatai.frp.server.service;

import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.frp.server.config.FrpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Startup
@ApplicationScoped
public class TunnelService {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Inject
    Vertx vertx;

    @Inject
    FrpServerConfig frpServerConfig;

    @PostConstruct
    void init() {

        LOGGER.info("Need Link Tunnel Size is {}", frpServerConfig.tunnels().size());

        frpServerConfig.tunnels().forEach(tunnel -> {
            new TunnelLinker(vertx).link(tunnel);
        });
    }

}
