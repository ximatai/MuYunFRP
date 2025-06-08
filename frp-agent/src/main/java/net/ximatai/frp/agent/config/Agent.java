package net.ximatai.frp.agent.config;

public interface Agent {
    String name();

    ProxyType type();

    FrpTunnel frpTunnel();

    ProxyServer proxy();
}
