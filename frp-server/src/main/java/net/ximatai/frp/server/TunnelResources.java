package net.ximatai.frp.server;

import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.service.TunnelRuntimeRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Path("/api/tunnel")
public class TunnelResources {
    private static final String BASIC_PREFIX = "Basic ";
    private static final String AUTH_CHALLENGE = "Basic realm=\"MuYunFRP\"";

    @Inject
    FrpServerConfig serverConfig;

    @Inject
    TunnelRuntimeRegistry runtimeRegistry;

    @GET
    public Response tunnels(@HeaderParam("Authorization") String authorization) {
        if (!isAuthorized(authorization)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", AUTH_CHALLENGE)
                    .build();
        }
        return Response.ok(runtimeRegistry.list(serverConfig.tunnels())).build();
    }

    private boolean isAuthorized(String authorization) {
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return false;
        }
        try {
            String encoded = authorization.substring(BASIC_PREFIX.length());
            String credentials = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separatorIndex = credentials.indexOf(':');
            if (separatorIndex < 0) {
                return false;
            }
            String username = credentials.substring(0, separatorIndex);
            String password = credentials.substring(separatorIndex + 1);
            return serverConfig.management().username().equals(username)
                    && serverConfig.management().password().equals(password);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

}
