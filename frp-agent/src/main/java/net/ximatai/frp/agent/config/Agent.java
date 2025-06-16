package net.ximatai.frp.agent.config;

import io.smallrye.config.ConfigMapping;
import net.ximatai.frp.common.ProxyType;

@ConfigMapping(prefix = "frp-agent")
public interface Agent {
    ProxyType type();

    FrpTunnel frpTunnel();

    ProxyServer proxy();
}
