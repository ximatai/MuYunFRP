package net.ximatai.frp;

import io.vertx.core.Vertx;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.net.NetSocket;
import net.ximatai.frp.agent.config.Agent;
import net.ximatai.frp.agent.config.Auth;
import net.ximatai.frp.agent.config.FrpTunnel;
import net.ximatai.frp.agent.config.ProxyServer;
import net.ximatai.frp.agent.verticle.AgentLinkerVerticle;
import net.ximatai.frp.common.ProxyType;
import net.ximatai.frp.server.config.Tunnel;
import net.ximatai.frp.server.service.TunnelLinkerVerticle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP is a consumer of the TCP tunnel: headers and multipart payload must remain byte-exact. */
class MultipartFrpRegressionTest {
    @Test
    void preservesMultipartUploadsAcrossAuthenticatedTunnel() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            var upstream = vertx.createHttpServer().requestHandler(request ->
                    request.bodyHandler(body -> {
                        try {
                            request.response().putHeader("Connection", "close").end(digest(body.getBytes()));
                        } catch (Exception failure) {
                            request.response().setStatusCode(500).end();
                        }
                    })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            TestFixture fixture = startFixture(vertx, upstream.actualPort());
            byte[] file = new byte[524288];
            new Random(524288).nextBytes(file);
            var body = new ByteArrayOutputStream();
            body.write("--frp-regression\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            body.write(file);
            body.write("\r\n--frp-regression--\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] payload = body.toByteArray();
            byte[] request = ("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: multipart/form-data; boundary=frp-regression\r\nContent-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            try (Socket client = new Socket("127.0.0.1", fixture.publicPort)) {
                client.setSoTimeout(10000);
                client.setTcpNoDelay(true);
                var output = client.getOutputStream();
                output.write(request);
                // Keep individual payload writes: they become multiple DATA frames while target connect is gated.
                for (int offset = 0; offset < payload.length; offset += 4096) {
                    output.write(payload, offset, Math.min(4096, payload.length - offset));
                }
                output.flush();

                fixture.agent.awaitTargetConnectionRequest();
                awaitBufferedTargetData(fixture.agent, request.length + payload.length);
                fixture.agent.releaseTargetConnection();

                String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200"), "multipart upload failed: " + response);
                assertTrue(response.contains(digest(payload)), "multipart bytes changed in transit");
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static TestFixture startFixture(Vertx vertx, int upstreamPort) throws Exception {
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            TestPorts ports = randomPorts();
            String tunnelDeployment = null;
            try {
                Tunnel tunnel = Tunnel.createRecord("multipart-regression", ProxyType.tcp, ports.publicPort, ports.agentPort, "isolated-test-token");
                tunnelDeployment = vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                DelayedTargetAgentLinkerVerticle agent = new DelayedTargetAgentLinkerVerticle(agentConfig(ports.agentPort, upstreamPort));
                vertx.deployVerticle(agent).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                return new TestFixture(ports.publicPort, agent);
            } catch (Exception failure) {
                lastFailure = failure;
                if (tunnelDeployment != null) {
                    vertx.undeploy(tunnelDeployment).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
            }
        }
        throw new IllegalStateException("Unable to start multipart fixture after 20 attempts", lastFailure);
    }

    private static Agent agentConfig(int agentPort, int upstreamPort) {
        return new Agent() {
            public ProxyType type() { return ProxyType.tcp; }
            public String agentName() { return "multipart-test"; }
            public Auth auth() { return () -> "isolated-test-token"; }
            public FrpTunnel frpTunnel() {
                return new FrpTunnel() {
                    public String host() { return "127.0.0.1"; }
                    public int port() { return agentPort; }
                };
            }
            public ProxyServer proxy() {
                return new ProxyServer() {
                    public String host() { return "127.0.0.1"; }
                    public int port() { return upstreamPort; }
                };
            }
        };
    }

    private static void awaitBufferedTargetData(AgentLinkerVerticle agent, int expectedBytes) throws Exception {
        var requestsField = AgentLinkerVerticle.class.getDeclaredField("pendingRequests");
        requestsField.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            @SuppressWarnings("unchecked")
            var requests = (java.util.Map<String, Object>) requestsField.get(agent);
            for (Object request : requests.values()) {
                var bufferedBytes = request.getClass().getDeclaredField("bufferedBytes");
                bufferedBytes.setAccessible(true);
                if (bufferedBytes.getInt(request) >= expectedBytes) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("request DATA did not arrive before target connection was released");
    }

    private static TestPorts randomPorts() {
        Set<Integer> ports = new HashSet<>();
        while (ports.size() < 2) {
            ports.add(ThreadLocalRandom.current().nextInt(20_000, 60_000));
        }
        int[] values = ports.stream().mapToInt(Integer::intValue).toArray();
        return new TestPorts(values[0], values[1]);
    }

    private record TestPorts(int publicPort, int agentPort) { }

    private record TestFixture(int publicPort, DelayedTargetAgentLinkerVerticle agent) { }

    private static final class DelayedTargetAgentLinkerVerticle extends AgentLinkerVerticle {
        private final Promise<Void> targetConnectionRequested = Promise.promise();
        private final Promise<Void> targetConnectionPermit = Promise.promise();

        private DelayedTargetAgentLinkerVerticle(Agent agent) {
            super(agent);
        }

        @Override
        protected Future<NetSocket> connectTarget(ProxyServer proxyServer) {
            targetConnectionRequested.tryComplete();
            return targetConnectionPermit.future().compose(ignored -> super.connectTarget(proxyServer));
        }

        private void awaitTargetConnectionRequest() throws Exception {
            targetConnectionRequested.future().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        private void releaseTargetConnection() {
            targetConnectionPermit.tryComplete();
        }
    }
}
