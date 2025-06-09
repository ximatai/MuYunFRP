package net.ximatai.frp.agent.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "frp-agent")
public interface Agent {
    ProxyType type();

    FrpTunnel frpTunnel();

    ProxyServer proxy();
}
