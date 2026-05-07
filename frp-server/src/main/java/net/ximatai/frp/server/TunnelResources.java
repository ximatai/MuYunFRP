package net.ximatai.frp.server;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.service.TunnelManager;
import net.ximatai.frp.server.service.TunnelRuntimeRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Path("/api/tunnels")
@Produces(MediaType.APPLICATION_JSON)
public class TunnelResources {
    private static final String BASIC_PREFIX = "Basic ";
    private static final String AUTH_CHALLENGE = "Basic realm=\"MuYunFRP\"";

    @Inject
    FrpServerConfig serverConfig;

    @Inject
    TunnelManager tunnelManager;

    @GET
    public Response tunnels(@HeaderParam("Authorization") String authorization) {
        Response unauthorized = unauthorizedResponse(authorization);
        if (unauthorized != null) {
            return unauthorized;
        }
        return Response.ok(tunnelManager.list()).build();
    }

    @GET
    @Path("/{name}")
    public Response tunnel(@HeaderParam("Authorization") String authorization, @PathParam("name") String name) {
        Response unauthorized = unauthorizedResponse(authorization);
        if (unauthorized != null) {
            return unauthorized;
        }
        return tunnelManager.get(name)
                .map(runtime -> Response.ok(runtime).build())
                .orElseGet(() -> error(Response.Status.NOT_FOUND.getStatusCode(), "TUNNEL_NOT_FOUND", "Tunnel not found: " + name));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@HeaderParam("Authorization") String authorization, TunnelManager.CreateTunnelRequest request) {
        Response unauthorized = unauthorizedResponse(authorization);
        if (unauthorized != null) {
            return unauthorized;
        }
        try {
            TunnelManager.CreateTunnelResult result = tunnelManager.create(request);
            return Response.status(Response.Status.CREATED)
                    .entity(CreateTunnelResponse.from(result))
                    .build();
        } catch (TunnelManager.TunnelOperationException ex) {
            return error(ex.status(), ex.error(), ex.getMessage());
        }
    }

    @DELETE
    @Path("/{name}")
    public Response delete(@HeaderParam("Authorization") String authorization, @PathParam("name") String name) {
        Response unauthorized = unauthorizedResponse(authorization);
        if (unauthorized != null) {
            return unauthorized;
        }
        try {
            if (!tunnelManager.delete(name)) {
                return error(Response.Status.NOT_FOUND.getStatusCode(), "TUNNEL_NOT_FOUND", "Tunnel not found: " + name);
            }
            return Response.noContent().build();
        } catch (TunnelManager.TunnelOperationException ex) {
            return error(ex.status(), ex.error(), ex.getMessage());
        }
    }

    @POST
    @Path("/{name}/restart")
    public Response restart(@HeaderParam("Authorization") String authorization, @PathParam("name") String name) {
        Response unauthorized = unauthorizedResponse(authorization);
        if (unauthorized != null) {
            return unauthorized;
        }
        try {
            return tunnelManager.restart(name)
                    .map(runtime -> Response.ok(runtime).build())
                    .orElseGet(() -> error(Response.Status.NOT_FOUND.getStatusCode(), "TUNNEL_NOT_FOUND", "Tunnel not found: " + name));
        } catch (TunnelManager.TunnelOperationException ex) {
            return error(ex.status(), ex.error(), ex.getMessage());
        }
    }

    @POST
    @Path("/{name}/token/reset")
    public Response resetToken(@HeaderParam("Authorization") String authorization, @PathParam("name") String name) {
        Response unauthorized = unauthorizedResponse(authorization);
        if (unauthorized != null) {
            return unauthorized;
        }
        try {
            return tunnelManager.resetToken(name)
                    .map(result -> Response.ok(CreateTunnelResponse.from(result)).build())
                    .orElseGet(() -> error(Response.Status.NOT_FOUND.getStatusCode(), "TUNNEL_NOT_FOUND", "Tunnel not found: " + name));
        } catch (TunnelManager.TunnelOperationException ex) {
            return error(ex.status(), ex.error(), ex.getMessage());
        }
    }

    private Response unauthorizedResponse(String authorization) {
        if (isAuthorized(authorization)) {
            return null;
        }
        return Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", AUTH_CHALLENGE)
                .build();
    }

    private Response error(int status, String error, String message) {
        return Response.status(status).entity(new ErrorResponse(error, message)).build();
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

    public record CreateTunnelResponse(
            String name,
            String type,
            int openPort,
            int agentPort,
            String status,
            String agentToken
    ) {
        static CreateTunnelResponse from(TunnelManager.CreateTunnelResult result) {
            TunnelRuntimeRegistry.TunnelRuntime tunnel = result.tunnel();
            return new CreateTunnelResponse(
                    tunnel.name(),
                    tunnel.type().name(),
                    tunnel.openPort(),
                    tunnel.agentPort(),
                    tunnel.status().name(),
                    result.agentToken()
            );
        }
    }

    public record ErrorResponse(String error, String message) {
    }
}
