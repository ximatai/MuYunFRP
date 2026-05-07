package net.ximatai.frp.server;

import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.service.TunnelRuntimeRegistry;

@Path("/api/tunnel")
public class TunnelResources {

    @Inject
    FrpServerConfig serverConfig;

    @Inject
    TunnelRuntimeRegistry runtimeRegistry;

    @GET
    public Response tunnels(@HeaderParam("Authorization") String authorization) {
        String expected = "Bearer " + serverConfig.management().token();
        if (authorization == null || !authorization.equals(expected)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(runtimeRegistry.list(serverConfig.tunnels())).build();
    }

}
