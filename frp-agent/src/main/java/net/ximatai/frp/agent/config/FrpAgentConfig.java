package net.ximatai.frp.agent.config;

import io.smallrye.config.ConfigMapping;

import java.util.List;

@ConfigMapping(prefix = "frp-agent")
public interface FrpAgentConfig {
    List<Agent> agents();
}
