package net.ximatai.frp.server.config;

public interface Tunnel {

    /**
     * 隧道名称
     *
     * @return 隧道名称
     */
    String name();

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
        return new TunnelRecord(name(), openPort(), agentPort());
    }

    record TunnelRecord(String name, int openPort, int agentPort) implements Tunnel {
    }

}
