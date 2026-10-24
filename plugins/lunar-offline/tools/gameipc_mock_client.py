"""模拟 Lunar 游戏进程的 gameipc 客户端，验证 LunarGameIpcServer 的完整协议。

用法: python gameipc_mock_client.py <port>
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import uuid as uuidlib

WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


# ---------- protobuf 极简实现 ----------

def varint(value):
    out = b""
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out


def tag(field, wire):
    return varint((field << 3) | wire)


def pb_bytes(field, data):
    return tag(field, 2) + varint(len(data)) + data


def pb_str(field, text):
    return pb_bytes(field, text.encode("utf-8"))


def pb_fixed64(field, value):
    return tag(field, 1) + struct.pack("<Q", value & 0xFFFFFFFFFFFFFFFF)


def pb_bool(field, value):
    return tag(field, 0) + varint(1 if value else 0)


def read_varint(buf, pos):
    result, shift = 0, 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def parse(buf):
    """返回 [(field, wire, value)]"""
    fields = []
    pos = 0
    while pos < len(buf):
        key, pos = read_varint(buf, pos)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, pos = read_varint(buf, pos)
        elif wire == 1:
            value = struct.unpack("<Q", buf[pos:pos + 8])[0]
            pos += 8
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            value = buf[pos:pos + length]
            pos += length
        else:
            raise ValueError(f"wire {wire}")
        fields.append((field, wire, value))
    return fields


def uuid_to_pb(u):
    value = u.int if hasattr(u, "int") else int(u)
    return pb_fixed64(1, value >> 64) + pb_fixed64(2, value & 0xFFFFFFFFFFFFFFFF)


def pb_to_uuid(buf):
    high = low = 0
    for field, wire, value in parse(buf):
        if field == 1:
            high = value
        elif field == 2:
            low = value
    return (high << 64) | low


# ---------- WebSocket ----------

def ws_connect(port, installation_id):
    sock = socket.create_connection(("127.0.0.1", port), timeout=15)
    handshake_pb = pb_str(1, "test-launch-id") + pb_str(2, str(os.getpid())) + pb_str(3, installation_id)
    handshake_b64 = base64.b64encode(handshake_pb).decode()
    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        "GET / HTTP/1.1\r\n"
        "Host: 127.0.0.1:%d\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "lc-handshake: %s\r\n"
        "\r\n" % (port, key, handshake_b64)
    )
    sock.sendall(request.encode())
    response = b""
    while b"\r\n\r\n" not in response:
        response += sock.recv(4096)
    head, _, rest = response.partition(b"\r\n\r\n")
    head_text = head.decode()
    assert "101" in head_text.split("\r\n")[0], head_text
    expected = base64.b64encode(hashlib.sha1((key + WS_MAGIC).encode()).digest()).decode()
    assert expected in head_text, "Sec-WebSocket-Accept 不匹配: " + head_text
    print("PASS websocket upgrade（握手被接受）")
    return sock, rest


def ws_send(sock, payload):
    header = bytes([0x82])  # FIN + binary
    length = len(payload)
    mask = os.urandom(4)
    if length < 126:
        header += bytes([0x80 | length])
    elif length < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", length)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", length)
    masked = bytes(payload[i] ^ mask[i % 4] for i in range(length))
    sock.sendall(header + mask + masked)


def ws_recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("连接被关闭")
        data += chunk
    return data


def ws_recv(sock):
    first, second = ws_recv_exact(sock, 2)
    assert first & 0x80, "FIN 未置位"
    opcode = first & 0x0F
    assert opcode == 2, f"期望 binary 帧，收到 opcode={opcode}"
    length = second & 0x7F
    if length == 126:
        length = struct.unpack(">H", ws_recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", ws_recv_exact(sock, 8))[0]
    assert not (second & 0x80), "服务端帧不应有掩码"
    return ws_recv_exact(sock, length)


# ---------- 测试 ----------

def request(sock, service, method, input_pb=b""):
    request_id = os.urandom(4)
    envelope = (pb_bytes(1, request_id)
                + pb_str(2, service)
                + pb_str(3, method)
                + pb_bytes(4, input_pb))
    ws_send(sock, envelope)
    return request_id, ws_recv(sock)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 28191
    sock, buffered = ws_connect(port, "INSTALLATION-ID")

    # 1) RefreshAccount：用 accounts.json 里激活账户的 MC profile uuid
    accounts_path = os.path.join(os.environ["USERPROFILE"], ".lunarclient", "settings", "game", "accounts.json")
    data = json.load(open(accounts_path, encoding="utf-8"))
    account = next(iter(data["accounts"].values()))
    profile_uuid = uuidlib.UUID(account["minecraftProfile"]["id"])
    request_id, response = request(
        sock,
        "lunarclient.gameipc.auth.v1.AuthService",
        "RefreshAccount",
        uuid_to_pb(profile_uuid),
    )
    # 响应信封：GameboundIPCMessage{rpcResponse(1){requestId(1), output(2)}}
    fields = parse(response)
    assert fields and fields[0][0] == 1, "缺少 rpcResponse 字段"
    rpc = dict((f[0], f[2]) for f in parse(fields[0][2]))
    assert rpc[1] == request_id, f"requestId 不匹配: {rpc[1].hex()} != {request_id.hex()}"
    output = rpc[2]
    success = error = None
    for field, wire, value in parse(output):
        if field == 1:
            success = bool(value)
        elif field == 2:
            error = value
    print(f"RefreshAccount -> success={success} error={error}")
    assert success is True, "RefreshAccount 应当成功"
    print("PASS RefreshAccount")

    # 2) 未知方法：官方行为是不回应 —— 用短超时确认没有响应帧
    sock.settimeout(1.5)
    try:
        request(sock, "lunarclient.gameipc.auth.v1.AuthService", "NotAMethod")
        print("FAIL 未知方法不应有响应")
        sys.exit(1)
    except (socket.timeout, TimeoutError):
        print("PASS 未知方法被忽略（无响应，符合官方行为）")

    # 3) chat 空响应
    sock.settimeout(15)
    request_id, response = request(
        sock,
        "lunarclient.gameipc.chat.v1.ChatService",
        "ReceiveMessage",
        pb_str(2, "hello"),
    )
    fields = parse(response)
    rpc = dict((f[0], f[2]) for f in parse(fields[0][2]))
    assert rpc[1] == request_id
    print("PASS chat.ReceiveMessage 空响应")

    print("ALL PASS")


if __name__ == "__main__":
    main()
