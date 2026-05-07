package net.ximatai.frp.server.service;

import jakarta.enterprise.context.ApplicationScoped;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.Tunnel;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TunnelRuntimeRegistry {
    private final Map<String, TunnelRuntime> runtimes = new ConcurrentHashMap<>();

    public void registerTunnel(Tunnel tunnel) {
        runtimes.putIfAbsent(tunnel.name(), TunnelRuntime.from(tunnel, TunnelStatus.STOPPED, null));
    }

    public void removeTunnel(String tunnelName) {
        runtimes.remove(tunnelName);
    }

    public void markStatus(Tunnel tunnel, TunnelStatus status) {
        markStatus(tunnel, status, null);
    }

    public void markStatus(Tunnel tunnel, TunnelStatus status, String failureReason) {
        runtimes.compute(tunnel.name(), (name, current) -> {
            TunnelRuntime base = current == null ? TunnelRuntime.from(tunnel, status, failureReason) : current;
            return base.withTunnel(tunnel).withStatus(status, failureReason);
        });
    }

    public void markAgentOnline(Tunnel tunnel, String agentName, String sessionId) {
        runtimes.compute(tunnel.name(), (name, current) -> {
            TunnelRuntime base = current == null ? TunnelRuntime.from(tunnel, TunnelStatus.LISTENING, null) : current;
            return base.withTunnel(tunnel).withAgentOnline(agentName, sessionId);
        });
    }

    public void markAgentOffline(Tunnel tunnel, String sessionId) {
        runtimes.compute(tunnel.name(), (name, current) -> {
            if (current == null || (current.sessionId() != null && !current.sessionId().equals(sessionId))) {
                return current;
            }
            return current.withAgentOffline();
        });
    }

    public void updateActiveConnections(Tunnel tunnel, String sessionId, int activeConnections) {
        runtimes.computeIfPresent(tunnel.name(), (name, current) -> {
            if (current.sessionId() == null || !current.sessionId().equals(sessionId)) {
                return current;
            }
            return current.withActiveConnections(activeConnections);
        });
    }

    public void touchAgent(Tunnel tunnel, String sessionId) {
        runtimes.computeIfPresent(tunnel.name(), (name, current) -> {
            if (current.sessionId() == null || !current.sessionId().equals(sessionId)) {
                return current;
            }
            return current.withLastSeenAt(Instant.now());
        });
    }

    public List<TunnelRuntime> list(Collection<? extends Tunnel> tunnels) {
        return tunnels.stream()
                .map(tunnel -> runtimes.getOrDefault(tunnel.name(), TunnelRuntime.from(tunnel, TunnelStatus.STOPPED, null)))
                .toList();
    }

    public TunnelRuntime get(Tunnel tunnel) {
        return runtimes.getOrDefault(tunnel.name(), TunnelRuntime.from(tunnel, TunnelStatus.STOPPED, null));
    }

    public record TunnelRuntime(
            String name,
            ProxyType type,
            int openPort,
            int agentPort,
            boolean tokenConfigured,
            TunnelStatus status,
            String failureReason,
            boolean agentOnline,
            String agentName,
            String sessionId,
            int activeConnections,
            Instant connectedAt,
            Instant lastSeenAt
    ) {
        static TunnelRuntime from(Tunnel tunnel, TunnelStatus status, String failureReason) {
            return new TunnelRuntime(
                    tunnel.name(),
                    tunnel.type(),
                    tunnel.openPort(),
                    tunnel.agentPort(),
                    tunnel.tokenHash() != null,
                    status,
                    failureReason,
                    false,
                    null,
                    null,
                    0,
                    null,
                    null
            );
        }

        TunnelRuntime withTunnel(Tunnel tunnel) {
            return new TunnelRuntime(
                    tunnel.name(),
                    tunnel.type(),
                    tunnel.openPort(),
                    tunnel.agentPort(),
                    tunnel.tokenHash() != null,
                    status,
                    failureReason,
                    agentOnline,
                    agentName,
                    sessionId,
                    activeConnections,
                    connectedAt,
                    lastSeenAt
            );
        }

        TunnelRuntime withStatus(TunnelStatus status, String failureReason) {
            return new TunnelRuntime(
                    name,
                    type,
                    openPort,
                    agentPort,
                    tokenConfigured,
                    status,
                    failureReason,
                    agentOnline,
                    agentName,
                    sessionId,
                    activeConnections,
                    connectedAt,
                    lastSeenAt
            );
        }

        TunnelRuntime withAgentOnline(String agentName, String sessionId) {
            Instant now = Instant.now();
            return new TunnelRuntime(
                    name,
                    type,
                    openPort,
                    agentPort,
                    tokenConfigured,
                    status,
                    failureReason,
                    true,
                    agentName,
                    sessionId,
                    activeConnections,
                    now,
                    now
            );
        }

        TunnelRuntime withAgentOffline() {
            return new TunnelRuntime(
                    name,
                    type,
                    openPort,
                    agentPort,
                    tokenConfigured,
                    status,
                    failureReason,
                    false,
                    null,
                    null,
                    0,
                    null,
                    null
            );
        }

        TunnelRuntime withActiveConnections(int activeConnections) {
            return with(activeConnections, Instant.now());
        }

        TunnelRuntime withLastSeenAt(Instant lastSeenAt) {
            return with(activeConnections, lastSeenAt);
        }

        private TunnelRuntime with(int activeConnections, Instant lastSeenAt) {
            return new TunnelRuntime(
                    name,
                    type,
                    openPort,
                    agentPort,
                    tokenConfigured,
                    status,
                    failureReason,
                    agentOnline,
                    agentName,
                    sessionId,
                    activeConnections,
                    connectedAt,
                    lastSeenAt
            );
        }
    }
}
