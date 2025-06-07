package net.ximatai.frp.server.config;

import io.smallrye.config.ConfigMapping;

import java.util.List;

@ConfigMapping(prefix = "frp")
public interface FrpConfig {
    ManagementConfig management();

    List<Tunnel> tunnels();

}
