package net.ximatai.frp.server.config;

import net.ximatai.frp.common.ProxyType;

public interface Tunnel {

    /**
     * 隧道名称
     *
     * @return 隧道名称
     */
    String name();

    ProxyType type();

    /**
     * 对外暴露的代理端口，开放给用户访问
     *
     * @return 代理端口
     */
    int openPort();

    /**
     * 给客户端使用的端口
     *
     * @return 客户端使用的端口
     */
    int agentPort();

    String token();

    default TunnelRecord toRecord() {
        return new TunnelRecord(name(), type(), openPort(), agentPort(), true);
    }

    static Tunnel createRecord(String name, ProxyType type, int openPort, int agentPort) {
        return createRecord(name, type, openPort, agentPort, "test-token");
    }

    static Tunnel createRecord(String name, ProxyType type, int openPort, int agentPort, String token) {
        return new TunnelConfig(name, type, openPort, agentPort, token);
    }

    record TunnelConfig(String name, ProxyType type, int openPort, int agentPort, String token) implements Tunnel {
    }

    record TunnelRecord(String name, ProxyType type, int openPort, int agentPort, boolean tokenConfigured) {
    }

}
