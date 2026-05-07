package net.ximatai.frp;

import jakarta.ws.rs.core.Response;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.TunnelResources;
import net.ximatai.frp.server.config.FrpServerConfig;
import net.ximatai.frp.server.config.ManagementConfig;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelRuntimeRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

class ManagementApiTest {

    private final FrpServerConfig serverConfig = new FrpServerConfig() {
        @Override
        public ManagementConfig management() {
            return new ManagementConfig() {
                @Override
                public int port() {
                    return 8089;
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
        public List<Tunnel> tunnels() {
            return List.of(Tunnel.createRecord("测试", ProxyType.tcp, 8082, 8083, "tunnel-token"));
        }
    };

    @Test
    void shouldRejectTunnelApiWithoutBasicAuth() throws Exception {
        Response response = resource().tunnels(null);

        Assertions.assertEquals(401, response.getStatus());
        Assertions.assertEquals("Basic realm=\"MuYunFRP\"", response.getHeaderString("WWW-Authenticate"));
    }

    @Test
    void shouldRejectTunnelApiWithWrongBasicAuth() throws Exception {
        Response response = resource().tunnels(basicAuth("admin", "wrong-password"));

        Assertions.assertEquals(401, response.getStatus());
    }

    @Test
    void shouldReturnTunnelRuntimeWithBasicAuth() throws Exception {
        Response response = resource().tunnels(basicAuth("admin", "password"));

        Assertions.assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        List<TunnelRuntimeRegistry.TunnelRuntime> body = (List<TunnelRuntimeRegistry.TunnelRuntime>) response.getEntity();
        Assertions.assertEquals(1, body.size());
        Assertions.assertTrue(body.getFirst().tokenConfigured());
        Assertions.assertFalse(body.getFirst().agentOnline());
    }

    private TunnelResources resource() throws Exception {
        TunnelRuntimeRegistry registry = new TunnelRuntimeRegistry();
        serverConfig.tunnels().forEach(registry::registerTunnel);

        TunnelResources resource = new TunnelResources();
        setField(resource, "serverConfig", serverConfig);
        setField(resource, "runtimeRegistry", registry);
        return resource;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String basicAuth(String username, String password) {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
