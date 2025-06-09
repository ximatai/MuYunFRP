package net.ximatai.frp.server;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.config.Tunnel;

import java.util.List;

@Path("/api/tunnel")
public class TunnelResources {

    @Inject
    FrpServerConfig serverConfig;

    @GET
    public List<Tunnel.TunnelRecord> tunnels() {
        return serverConfig.tunnels().stream().map(Tunnel::toRecord).toList();
    }

}
