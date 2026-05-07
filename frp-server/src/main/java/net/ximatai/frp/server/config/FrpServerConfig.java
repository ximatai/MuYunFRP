package net.ximatai.frp.server.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "frp-server")
public interface FrpServerConfig {
    ManagementConfig management();

    TunnelStoreConfig tunnelStore();
}
