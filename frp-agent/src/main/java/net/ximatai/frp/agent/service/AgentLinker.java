package net.ximatai.frp.agent.service;

import io.vertx.core.Vertx;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.FrpServer;
import net.ximatai.frp.agent.config.ProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentLinker {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private final Vertx vertx;

    public AgentLinker(Vertx vertx) {
        this.vertx = vertx;
    }

    public void link(Agent agent) {
        FrpServer frpServer = agent.frpServer();
        ProxyServer proxyServer = agent.proxy();

    }

}
