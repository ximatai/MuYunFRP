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

    default TunnelRecord toRecord() {
        return new TunnelRecord(name(), type(), openPort(), agentPort());
    }

    static Tunnel createRecord(String name, ProxyType type, int openPort, int agentPort) {
        return new TunnelRecord(name, type, openPort, agentPort);
    }

    record TunnelRecord(String name, ProxyType type, int openPort, int agentPort) implements Tunnel {
    }

}
