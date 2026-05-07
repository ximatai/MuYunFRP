package net.ximatai.frp.common;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MessageUtil {

    public static final int OPERATION_WIDTH = 17; // 标志位一个字节，UUID 16个字节
    public static final int CONTROL_WIDTH = 1; // 控制帧只有操作码，不携带请求ID

    public static Buffer buildOperationMessage(String requestId, OperationType type) {
        Buffer frame = Buffer.buffer(OPERATION_WIDTH);

        // 添加操作码
        frame.appendByte(type.getValue());

        // 添加请求ID (16字节)
        UUID uuid = UUID.fromString(requestId);
        frame.appendLong(uuid.getMostSignificantBits());
        frame.appendLong(uuid.getLeastSignificantBits());

        return frame;
    }

    public static Buffer buildDataMessage(String requestId, Buffer data) {
        Buffer frame = Buffer.buffer(OPERATION_WIDTH + data.length());

        // 添加操作码
        frame.appendByte(OperationType.DATA.getValue());

        // 添加请求ID (16字节)
        UUID uuid = UUID.fromString(requestId);
        frame.appendLong(uuid.getMostSignificantBits());
        frame.appendLong(uuid.getLeastSignificantBits());
        frame.appendBuffer(data);

        return frame;
    }

    public static Buffer buildControlMessage(OperationType type, JsonObject payload) {
        Buffer frame = Buffer.buffer(CONTROL_WIDTH);
        frame.appendByte(type.getValue());
        if (payload != null) {
            frame.appendBytes(payload.encode().getBytes(StandardCharsets.UTF_8));
        }
        return frame;
    }

    public static OperationType getOperationType(Buffer data) {
        return OperationType.fromValue(data.getByte(0));
    }

    public static String getRequestId(Buffer data) {
        Buffer requestIdBuffer = data.getBuffer(1, OPERATION_WIDTH);
        return new UUID(
                requestIdBuffer.getLong(0),
                requestIdBuffer.getLong(8)
        ).toString();
    }

    public static Buffer getPayload(Buffer data) {
        return data.getBuffer(OPERATION_WIDTH, data.length());
    }

    public static JsonObject getControlPayload(Buffer data) {
        if (data.length() <= CONTROL_WIDTH) {
            return new JsonObject();
        }
        return new JsonObject(data.getString(CONTROL_WIDTH, data.length()));
    }

    public static boolean isControlOperation(OperationType operationType) {
        return operationType == OperationType.AUTH
                || operationType == OperationType.AUTH_OK
                || operationType == OperationType.AUTH_FAIL;
    }
}
