package net.ximatai.frp.shared;

public class Constants {
    // 协议常量
    public static final byte CONNECT = 0x01;
    public static final byte DATA = 0x02;
    public static final byte CLOSE = 0x03;

    /**
     * 心跳间隔时间，单位毫秒
     */
    public static final long HEARTBEAT_INTERVAL = 30000;

    /**
     * WebSocket帧最大长度
     */
    public static final int MAX_WEBSOCKET_FRAME_SIZE = 65536;

    /**
     * 标志位一个字节，UUID 16个字节
     */
    public static final int CONTROL_WIDTH = 17;

    private Constants() {
    }
}
