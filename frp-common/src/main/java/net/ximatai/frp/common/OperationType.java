package net.ximatai.frp.common;

public enum OperationType {
    CONNECT((byte) 0x01),
    DATA((byte) 0x02),
    CLOSE((byte) 0x03);

    private final byte value;

    OperationType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    // 可选：根据字节值获取对应的枚举常量
    public static OperationType fromValue(byte value) {
        for (OperationType type : OperationType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown ControlType value: " + value);
    }
}
