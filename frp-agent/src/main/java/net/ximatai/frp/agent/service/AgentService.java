package net.ximatai.frp.agent.service;

import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.frp.agent.config.FrpAgentConfig;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Startup
@ApplicationScoped
public class AgentService {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Inject
    Vertx vertx;

    @Inject
    FrpAgentConfig agentConfig;

    @PostConstruct
    void init() {

        LOGGER.info("Need Link Agent Size is {}", agentConfig.agents().size());

        agentConfig.agents().forEach(agent -> {
            AgentLinkerVerticle linker = new AgentLinkerVerticle(agent);
            vertx.deployVerticle(linker);
        });

    }

}
