package net.ximatai.frp.server.config;

import io.smallrye.config.ConfigMapping;

import java.util.List;

@ConfigMapping(prefix = "frp-server")
public interface FrpServerConfig {
    ManagementConfig management();

    List<Tunnel> tunnels();
}
