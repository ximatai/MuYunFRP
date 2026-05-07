package net.ximatai.frp.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.config.Tunnel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TunnelStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelStore.class);
    private static final TypeReference<List<Tunnel.TunnelConfig>> TUNNEL_LIST_TYPE = new TypeReference<>() {
    };

    private final Object lock = new Object();

    @Inject
    FrpServerConfig serverConfig;

    @Inject
    ObjectMapper objectMapper;

    public List<Tunnel.TunnelConfig> load() {
        synchronized (lock) {
            Path path = storePath();
            ensureStoreFile(path);
            try {
                List<Tunnel.TunnelConfig> tunnels = objectMapper.readValue(path.toFile(), TUNNEL_LIST_TYPE);
                validate(tunnels, serverConfig.management().port());
                return new ArrayList<>(tunnels);
            } catch (IOException ex) {
                throw new TunnelStoreException("Failed to read tunnel store", ex);
            }
        }
    }

    public void save(List<Tunnel.TunnelConfig> tunnels) {
        synchronized (lock) {
            validate(tunnels, serverConfig.management().port());
            write(tunnels);
        }
    }

    public List<Tunnel.TunnelConfig> mutate(StoreMutation mutation) {
        synchronized (lock) {
            List<Tunnel.TunnelConfig> tunnels = load();
            List<Tunnel.TunnelConfig> mutated = mutation.apply(new ArrayList<>(tunnels));
            validate(mutated, serverConfig.management().port());
            write(mutated);
            return mutated;
        }
    }

    public void validate(List<Tunnel.TunnelConfig> tunnels, int managementPort) {
        Map<String, Tunnel.TunnelConfig> byName = new LinkedHashMap<>();
        Map<Integer, String> usedPorts = new LinkedHashMap<>();
        for (Tunnel.TunnelConfig tunnel : tunnels) {
            validateTunnel(tunnel, managementPort);
            if (byName.putIfAbsent(tunnel.name(), tunnel) != null) {
                throw new TunnelValidationException("Duplicate tunnel name: " + tunnel.name());
            }
            claimPort(usedPorts, tunnel.openPort(), tunnel.name());
            claimPort(usedPorts, tunnel.agentPort(), tunnel.name());
        }
    }

    public void validateTunnel(Tunnel tunnel, int managementPort) {
        if (tunnel.name() == null || !tunnel.name().matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new TunnelValidationException("Invalid tunnel name: " + tunnel.name());
        }
        if (tunnel.type() != ProxyType.tcp) {
            throw new TunnelValidationException("Unsupported tunnel type: " + tunnel.type());
        }
        validatePort(tunnel.openPort(), "openPort");
        validatePort(tunnel.agentPort(), "agentPort");
        if (tunnel.openPort() == tunnel.agentPort()) {
            throw new TunnelValidationException("openPort and agentPort must be different");
        }
        if (tunnel.openPort() == managementPort || tunnel.agentPort() == managementPort) {
            throw new TunnelValidationException("Tunnel ports must not conflict with management port");
        }
        if (tunnel.tokenHash() == null || !tunnel.tokenHash().isValid()) {
            throw new TunnelValidationException("Invalid tokenHash for tunnel: " + tunnel.name());
        }
    }

    public Path storePath() {
        return Path.of(serverConfig.tunnelStore().path());
    }

    private void ensureStoreFile(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                Files.writeString(path, "[]");
            }
        } catch (IOException ex) {
            throw new TunnelStoreException("Failed to initialize tunnel store", ex);
        }
    }

    private void write(List<Tunnel.TunnelConfig> tunnels) {
        Path path = storePath();
        ensureStoreFile(path);
        try {
            List<Tunnel.TunnelConfig> sorted = tunnels.stream()
                    .sorted(Comparator.comparing(Tunnel.TunnelConfig::name))
                    .toList();
            Path parent = path.getParent();
            Path temp = parent == null
                    ? Files.createTempFile(path.getFileName().toString(), ".tmp")
                    : Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), sorted);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                LOGGER.warn("Atomic move is not supported for tunnel store, falling back to replace");
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new TunnelStoreException("Failed to write tunnel store", ex);
        }
    }

    private void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65535) {
            throw new TunnelValidationException(fieldName + " must be between 1 and 65535");
        }
    }

    private void claimPort(Map<Integer, String> usedPorts, int port, String tunnelName) {
        String existing = usedPorts.putIfAbsent(port, tunnelName);
        if (existing != null) {
            throw new TunnelValidationException("Port " + port + " is already used by tunnel " + existing);
        }
    }

    public interface StoreMutation {
        List<Tunnel.TunnelConfig> apply(List<Tunnel.TunnelConfig> tunnels);
    }

    public static class TunnelStoreException extends RuntimeException {
        public TunnelStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class TunnelValidationException extends RuntimeException {
        public TunnelValidationException(String message) {
            super(message);
        }
    }
}
