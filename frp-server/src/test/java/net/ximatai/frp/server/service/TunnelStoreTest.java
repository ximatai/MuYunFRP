package net.ximatai.frp.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.config.ManagementConfig;
import net.ximatai.frp.server.config.TokenHash;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.config.TunnelStoreConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class TunnelStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateEmptyStoreWhenFileDoesNotExist() throws Exception {
        Path storePath = tempDir.resolve("config/tunnels.json");
        TunnelStore store = store(storePath, 8089);

        List<Tunnel.TunnelConfig> tunnels = store.load();

        Assertions.assertTrue(tunnels.isEmpty());
        Assertions.assertEquals("[]", Files.readString(storePath));
    }

    @Test
    void shouldWriteAndReadTunnels() throws Exception {
        Path storePath = tempDir.resolve("config/tunnels.json");
        TunnelStore store = store(storePath, 8089);
        Tunnel.TunnelConfig tunnel = (Tunnel.TunnelConfig) Tunnel.createRecord(
                "store_test",
                ProxyType.tcp,
                18082,
                18083,
                "token"
        );

        store.save(List.of(tunnel));
        List<Tunnel.TunnelConfig> loaded = store.load();

        Assertions.assertEquals(1, loaded.size());
        Assertions.assertEquals("store_test", loaded.getFirst().name());
        Assertions.assertNotNull(loaded.getFirst().tokenHash());
    }

    @Test
    void shouldRejectDuplicateTunnelNames() throws Exception {
        TunnelStore store = store(tempDir.resolve("tunnels.json"), 8089);
        Tunnel.TunnelConfig first = (Tunnel.TunnelConfig) Tunnel.createRecord("dup", ProxyType.tcp, 18082, 18083, "token");
        Tunnel.TunnelConfig second = (Tunnel.TunnelConfig) Tunnel.createRecord("dup", ProxyType.tcp, 18084, 18085, "token");

        Assertions.assertThrows(
                TunnelStore.TunnelValidationException.class,
                () -> store.save(List.of(first, second))
        );
    }

    @Test
    void shouldRejectManagementPortConflict() throws Exception {
        TunnelStore store = store(tempDir.resolve("tunnels.json"), 8089);
        Tunnel.TunnelConfig tunnel = (Tunnel.TunnelConfig) Tunnel.createRecord("conflict", ProxyType.tcp, 8089, 18083, "token");

        Assertions.assertThrows(
                TunnelStore.TunnelValidationException.class,
                () -> store.save(List.of(tunnel))
        );
    }

    @Test
    void shouldRejectInvalidTokenHash() throws Exception {
        TunnelStore store = store(tempDir.resolve("tunnels.json"), 8089);
        String now = java.time.Instant.now().toString();
        Tunnel.TunnelConfig tunnel = new Tunnel.TunnelConfig(
                "bad_hash",
                ProxyType.tcp,
                18082,
                18083,
                new TokenHash(TokenHash.ALGORITHM, 1, "bad", "bad"),
                now,
                now
        );

        Assertions.assertThrows(
                TunnelStore.TunnelValidationException.class,
                () -> store.save(List.of(tunnel))
        );
    }

    private TunnelStore store(Path storePath, int managementPort) throws Exception {
        TunnelStore store = new TunnelStore();
        setField(store, "objectMapper", new ObjectMapper());
        setField(store, "serverConfig", serverConfig(storePath, managementPort));
        return store;
    }

    private FrpServerConfig serverConfig(Path storePath, int managementPort) {
        return new FrpServerConfig() {
            @Override
            public ManagementConfig management() {
                return new ManagementConfig() {
                    @Override
                    public int port() {
                        return managementPort;
                    }

                    @Override
                    public String username() {
                        return "admin";
                    }

                    @Override
                    public String password() {
                        return "password";
                    }
                };
            }

            @Override
            public TunnelStoreConfig tunnelStore() {
                return () -> storePath.toString();
            }
        };
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
