package net.ximatai.frp.agent.config;

public interface Agent {
    String name();

    ProxyType type();

    FrpServer frpServer();

    ProxyServer proxy();
}
