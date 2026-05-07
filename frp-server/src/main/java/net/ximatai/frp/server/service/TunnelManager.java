package net.ximatai.frp.server.service;

import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.config.TokenHash;
import net.ximatai.frp.server.config.Tunnel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

@ApplicationScoped
public class TunnelManager {
    private final Object lock = new Object();
    private final Map<String, Tunnel.TunnelConfig> tunnels = new LinkedHashMap<>();
    private final Map<String, String> deployments = new LinkedHashMap<>();

    @Inject
    Vertx vertx;

    @Inject
    FrpServerConfig serverConfig;

    @Inject
    TunnelStore tunnelStore;

    @Inject
    TunnelRuntimeRegistry runtimeRegistry;

    @Inject
    TunnelTokenService tokenService;

    public void startAll() {
        synchronized (lock) {
            List<Tunnel.TunnelConfig> loaded = tunnelStore.load();
            List<String> started = new ArrayList<>();
            try {
                for (Tunnel.TunnelConfig tunnel : loaded) {
                    deployBlocking(tunnel);
                    tunnels.put(tunnel.name(), tunnel);
                    started.add(tunnel.name());
                }
            } catch (RuntimeException ex) {
                for (String tunnelName : started.reversed()) {
                    undeployBlocking(tunnelName);
                }
                tunnels.clear();
                throw ex;
            }
        }
    }

    public List<TunnelRuntimeRegistry.TunnelRuntime> list() {
        synchronized (lock) {
            return runtimeRegistry.list(tunnels.values());
        }
    }

    public Optional<TunnelRuntimeRegistry.TunnelRuntime> get(String name) {
        synchronized (lock) {
            Tunnel.TunnelConfig tunnel = tunnels.get(name);
            if (tunnel == null) {
                return Optional.empty();
            }
            return Optional.of(runtimeRegistry.get(tunnel));
        }
    }

    public CreateTunnelResult create(CreateTunnelRequest request) {
        synchronized (lock) {
            validateCreateRequest(request);
            String token = tokenService.generateToken();
            String now = Instant.now().toString();
            Tunnel.TunnelConfig tunnel = new Tunnel.TunnelConfig(
                    request.name(),
                    request.type(),
                    request.openPort(),
                    request.agentPort(),
                    TokenHash.create(token),
                    now,
                    now
            );
            validateNewTunnel(tunnel);
            deployBlocking(tunnel);
            tunnels.put(tunnel.name(), tunnel);
            try {
                tunnelStore.save(new ArrayList<>(tunnels.values()));
            } catch (RuntimeException ex) {
                try {
                    undeployBlocking(tunnel.name());
                } catch (RuntimeException stopEx) {
                    runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, rootMessage(stopEx));
                }
                tunnels.remove(tunnel.name());
                throw new TunnelOperationException("STORE_WRITE_FAILED", "Failed to write tunnel store", ex, 500);
            }
            return new CreateTunnelResult(runtimeRegistry.get(tunnel), token);
        }
    }

    public boolean delete(String name) {
        synchronized (lock) {
            Tunnel.TunnelConfig tunnel = tunnels.get(name);
            if (tunnel == null) {
                return false;
            }
            runtimeRegistry.markStatus(tunnel, TunnelStatus.STOPPING);
            try {
                undeployBlocking(name);
            } catch (RuntimeException ex) {
                runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, rootMessage(ex));
                throw ex;
            }
            tunnels.remove(name);
            try {
                tunnelStore.save(new ArrayList<>(tunnels.values()));
                runtimeRegistry.removeTunnel(name);
                return true;
            } catch (RuntimeException ex) {
                tunnels.put(name, tunnel);
                runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, "Failed to delete tunnel from store");
                try {
                    deployBlocking(tunnel);
                } catch (RuntimeException restoreEx) {
                    runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, "Failed to restore tunnel after delete failure");
                }
                throw new TunnelOperationException("STORE_WRITE_FAILED", "Failed to write tunnel store", ex, 500);
            }
        }
    }

    public Optional<TunnelRuntimeRegistry.TunnelRuntime> restart(String name) {
        synchronized (lock) {
            Tunnel.TunnelConfig tunnel = tunnels.get(name);
            if (tunnel == null) {
                return Optional.empty();
            }
            runtimeRegistry.markStatus(tunnel, TunnelStatus.STOPPING);
            try {
                undeployBlocking(name);
                deployBlocking(tunnel);
                return Optional.of(runtimeRegistry.get(tunnel));
            } catch (RuntimeException ex) {
                runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, rootMessage(ex));
                throw ex;
            }
        }
    }

    public Optional<CreateTunnelResult> resetToken(String name) {
        synchronized (lock) {
            Tunnel.TunnelConfig existing = tunnels.get(name);
            if (existing == null) {
                return Optional.empty();
            }
            String token = tokenService.generateToken();
            Tunnel.TunnelConfig updated = new Tunnel.TunnelConfig(
                    existing.name(),
                    existing.type(),
                    existing.openPort(),
                    existing.agentPort(),
                    TokenHash.create(token),
                    existing.createdAt(),
                    Instant.now().toString()
            );
            runtimeRegistry.markStatus(existing, TunnelStatus.STOPPING);
            try {
                undeployBlocking(name);
            } catch (RuntimeException ex) {
                runtimeRegistry.markStatus(existing, TunnelStatus.FAILED, rootMessage(ex));
                throw ex;
            }

            tunnels.put(name, updated);
            try {
                tunnelStore.save(new ArrayList<>(tunnels.values()));
            } catch (RuntimeException ex) {
                tunnels.put(name, existing);
                restoreExistingTunnel(existing, "Failed to restore tunnel after token reset store failure");
                throw new TunnelOperationException("STORE_WRITE_FAILED", "Failed to write tunnel store", ex, 500);
            }
            try {
                deployBlocking(updated);
            } catch (RuntimeException ex) {
                rollbackResetToken(existing, updated, ex);
                throw new TunnelOperationException("TUNNEL_START_FAILED", "Failed to restart tunnel after token reset", ex, 500);
            }
            return Optional.of(new CreateTunnelResult(runtimeRegistry.get(updated), token));
        }
    }

    private void rollbackResetToken(Tunnel.TunnelConfig existing, Tunnel.TunnelConfig updated, RuntimeException cause) {
        tunnels.put(existing.name(), existing);
        try {
            tunnelStore.save(new ArrayList<>(tunnels.values()));
        } catch (RuntimeException storeEx) {
            runtimeRegistry.markStatus(updated, TunnelStatus.FAILED, "Failed to rollback token reset in store");
            throw new TunnelOperationException("STORE_WRITE_FAILED", "Failed to rollback token reset in store", storeEx, 500);
        }
        if (!restoreExistingTunnel(existing, "Failed to restore tunnel after token reset failure")) {
            runtimeRegistry.markStatus(existing, TunnelStatus.FAILED, rootMessage(cause));
        }
    }

    private boolean restoreExistingTunnel(Tunnel.TunnelConfig tunnel, String failureReason) {
        tunnels.put(tunnel.name(), tunnel);
        if (deployments.containsKey(tunnel.name())) {
            return true;
        }
        try {
            deployBlocking(tunnel);
            return true;
        } catch (RuntimeException restoreEx) {
            runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, failureReason);
            return false;
        }
    }

    private void deployBlocking(Tunnel.TunnelConfig tunnel) {
        runtimeRegistry.registerTunnel(tunnel);
        runtimeRegistry.markStatus(tunnel, TunnelStatus.STARTING);
        TunnelLinkerVerticle linker = new TunnelLinkerVerticle(vertx, tunnel, runtimeRegistry);
        try {
            String deploymentId = vertx.deployVerticle(linker)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            deployments.put(tunnel.name(), deploymentId);
            runtimeRegistry.markStatus(tunnel, TunnelStatus.LISTENING);
        } catch (CompletionException ex) {
            runtimeRegistry.markStatus(tunnel, TunnelStatus.FAILED, rootMessage(ex));
            throw new TunnelOperationException("TUNNEL_START_FAILED", "Failed to start tunnel " + tunnel.name(), ex, 500);
        }
    }

    private void undeployBlocking(String tunnelName) {
        String deploymentId = deployments.get(tunnelName);
        if (deploymentId == null) {
            return;
        }
        try {
            vertx.undeploy(deploymentId)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            deployments.remove(tunnelName);
        } catch (CompletionException ex) {
            throw new TunnelOperationException("TUNNEL_STOP_FAILED", "Failed to stop tunnel " + tunnelName, ex, 500);
        }
    }

    private void validateCreateRequest(CreateTunnelRequest request) {
        if (request == null) {
            throw new TunnelOperationException("INVALID_REQUEST", "Request body is required", 400);
        }
        if (request.name() == null || !request.name().matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new TunnelOperationException("INVALID_TUNNEL_NAME", "Invalid tunnel name", 400);
        }
        if (request.type() != ProxyType.tcp) {
            throw new TunnelOperationException("UNSUPPORTED_TUNNEL_TYPE", "Only tcp tunnels are supported", 400);
        }
        if (request.openPort() < 1 || request.openPort() > 65535
                || request.agentPort() < 1 || request.agentPort() > 65535
                || request.openPort() == request.agentPort()) {
            throw new TunnelOperationException("INVALID_PORT", "Invalid tunnel ports", 400);
        }
        if (request.openPort() == serverConfig.management().port()
                || request.agentPort() == serverConfig.management().port()) {
            throw new TunnelOperationException("PORT_CONFLICT", "Tunnel ports must not conflict with management port", 409);
        }
    }

    private void validateNewTunnel(Tunnel.TunnelConfig tunnel) {
        if (tunnels.containsKey(tunnel.name())) {
            throw new TunnelOperationException("TUNNEL_ALREADY_EXISTS", "Tunnel already exists: " + tunnel.name(), 409);
        }
        for (Tunnel.TunnelConfig existing : tunnels.values()) {
            if (existing.openPort() == tunnel.openPort()
                    || existing.openPort() == tunnel.agentPort()
                    || existing.agentPort() == tunnel.openPort()
                    || existing.agentPort() == tunnel.agentPort()) {
                throw new TunnelOperationException("PORT_CONFLICT", "Tunnel port conflicts with " + existing.name(), 409);
            }
        }
        tunnelStore.validateTunnel(tunnel, serverConfig.management().port());
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    public record CreateTunnelRequest(String name, ProxyType type, int openPort, int agentPort) {
    }

    public record CreateTunnelResult(TunnelRuntimeRegistry.TunnelRuntime tunnel, String agentToken) {
    }

    public static class TunnelOperationException extends RuntimeException {
        private final String error;
        private final int status;

        public TunnelOperationException(String error, String message, int status) {
            super(message);
            this.error = error;
            this.status = status;
        }

        public TunnelOperationException(String error, String message, Throwable cause, int status) {
            super(message, cause);
            this.error = error;
            this.status = status;
        }

        public String error() {
            return error;
        }

        public int status() {
            return status;
        }
    }
}
