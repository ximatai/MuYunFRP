package net.ximatai.frp;

import io.vertx.core.Vertx;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;

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
            int publicPort;
            int agentPort;
            try (ServerSocket publicReservation = new ServerSocket(0);
                 ServerSocket agentReservation = new ServerSocket(0)) {
                publicPort = publicReservation.getLocalPort();
                agentPort = agentReservation.getLocalPort();
            }
            Tunnel tunnel = Tunnel.createRecord("multipart-regression", ProxyType.tcp, publicPort, agentPort, "isolated-test-token");
            vertx.deployVerticle(new TunnelLinkerVerticle(vertx, tunnel))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            Agent config = new Agent() {
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
                        public int port() { return upstream.actualPort(); }
                    };
                }
            };
            vertx.deployVerticle(new AgentLinkerVerticle(config))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (int size : new int[]{16384, 131072, 524288}) {
                for (int repeat = 0; repeat < 4; repeat++) {
                    byte[] file = new byte[size];
                    new Random(size + repeat).nextBytes(file);
                    var body = new ByteArrayOutputStream();
                    body.write("--frp-regression\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    body.write(file);
                    body.write("\r\n--frp-regression--\r\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] payload = body.toByteArray();
                    try (Socket client = new Socket("127.0.0.1", publicPort)) {
                        client.setSoTimeout(10000);
                        client.setTcpNoDelay(true);
                        var output = client.getOutputStream();
                        output.write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: multipart/form-data; boundary=frp-regression\r\nContent-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        // Separate header and payload writes exercise the original multipart ordering symptom.
                        for (int offset = 0; offset < payload.length; offset += 4096) {
                            output.write(payload, offset, Math.min(4096, payload.length - offset));
                        }
                        output.flush();
                        String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                        assertTrue(response.startsWith("HTTP/1.1 200"), "upload " + size + "/" + repeat + ": " + response);
                        assertTrue(response.contains(digest(payload)), "multipart bytes changed in transit");
                    }
                }
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
