#!/usr/bin/env python3
"""
End-to-end check: mock 1.8 server -> ViaProxy (+ViaEnchant plugin) -> mock 1.7.10 client.

The mock server opens an enchanting table and sends window properties 0-9. The mock client must receive the
clue properties (3-6) as custom payloads on the "ViaEnchant" channel, only once per change, and again after the
window is reopened.

Usage: e2e_test.py <ViaProxy.jar> <ViaEnchant.jar> [chat] [auto] [hello] [register] [client18] [1.12]

"chat" sets chat-fallback=true, "auto" sets chat-fallback=auto (default config of the plugin), otherwise it is false.
"hello" makes the mock client announce the Forge mod the way the mod does (a payload on the ViaEnchant channel), "register"
does it through Forge's REGISTER channel list. In auto mode there must be no chat messages then, and the server must
never see the announcement. "client18" connects as a 1.8 client, which must get neither payloads nor chat.

With "1.12" the mock server speaks 1.12.2 instead of 1.8, which sends the clue as separate enchantment id (4-6) and
level (7-9) properties. ViaRewind has to merge them back into the 1.8 layout, like it does for any newer server.
"""
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import zlib
import threading
import time

VIAPROXY_JAR, PLUGIN_JAR = sys.argv[1], sys.argv[2]
ARGS = sys.argv[3:]
CHAT_MODE = "chat" in ARGS
AUTO_MODE = "auto" in ARGS
HELLO = "hello" in ARGS
REGISTER = "register" in ARGS
CLIENT_18 = "client18" in ARGS
EXPECT_CHAT = CHAT_MODE or (AUTO_MODE and not HELLO and not REGISTER)
SERVER_SAW = []
SERVER_112 = "1.12" in ARGS
SERVER_PORT, PROXY_PORT = 25570, 25569


def varint(value):
    out = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        if value:
            out.append(part | 0x80)
        else:
            out.append(part)
            return bytes(out)


def string(text):
    data = text.encode()
    return varint(len(data)) + data


def packet(packet_id, payload=b""):
    body = varint(packet_id) + payload
    return varint(len(body)) + body


def read_varint(sock):
    result, shift = 0, 0
    while True:
        byte = sock.recv(1)
        if not byte:
            raise EOFError
        result |= (byte[0] & 0x7F) << shift
        if not byte[0] & 0x80:
            return result
        shift += 7


def read_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


def read_packet(sock, compressed=False):
    length = read_varint(sock)
    body = read_exact(sock, length)
    if compressed:
        # 1.8 compression format: varint uncompressed size (0 = not compressed) + data
        size, shift, index = 0, 0, 0
        while True:
            size |= (body[index] & 0x7F) << shift
            index += 1
            if not body[index - 1] & 0x80:
                break
            shift += 7
        body = zlib.decompress(body[index:]) if size else body[index:]
    # read the packet id out of the body
    result, shift, index = 0, 0, 0
    while True:
        result |= (body[index] & 0x7F) << shift
        index += 1
        if not body[index - 1] & 0x80:
            break
        shift += 7
    return result, body[index:]


def window_property(window_id, prop, value):
    return packet(0x31, struct.pack(">BhH", window_id, prop, value & 0xFFFF))


# Packet ids: (join game, open window, window items, window property, close window)
IDS = (0x23, 0x13, 0x14, 0x15, 0x12) if SERVER_112 else (0x01, 0x2D, 0x30, 0x31, 0x2E)


def send_table_burst(conn, window_id):
    if SERVER_112:
        # 1.9+ layout: 0-2 level requirements, 3 seed, 4-6 clue enchantment ids, 7-9 clue levels (-1 = no clue)
        values = [10, 20, 30, 1234, 32, 16, 0xFFFF, 3, 2, 0xFFFF]
    else:
        # 1.8.9 layout: 0-2 level requirements, 3 seed, 4-6 clue packed as enchantment id | level << 8 (-1 = no clue)
        values = [10, 20, 30, 1234, 32 | (3 << 8), 16 | (2 << 8), 0xFFFF]
    for prop, value in enumerate(values):
        conn.sendall(packet(IDS[3], struct.pack(">BhH", window_id, prop, value & 0xFFFF)))


def open_table(conn, window_id):
    conn.sendall(packet(IDS[1], struct.pack(">B", window_id) + string("minecraft:enchanting_table")
                        + string('{"text":"Enchant"}') + struct.pack(">B", 0)))
    conn.sendall(packet(IDS[2], struct.pack(">Bh", window_id, 38) + struct.pack(">h", -1) * 38))


def record_serverbound(conn):
    try:
        while True:
            packet_id, data = read_packet(conn)
            if packet_id == 0x17:  # 1.8 plugin message: string channel + data
                SERVER_SAW.append(data[1:1 + data[0]].decode())
    except Exception:
        pass


def mock_server(ready):
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", SERVER_PORT))
    listener.listen(1)
    ready.set()
    conn, _ = listener.accept()
    read_packet(conn)  # handshake
    read_packet(conn)  # login start
    conn.sendall(packet(0x02, string("11111111-2222-3333-4444-555555555555") + string("Tester")))
    threading.Thread(target=record_serverbound, args=(conn,), daemon=True).start()
    if SERVER_112:
        conn.sendall(packet(IDS[0], struct.pack(">iBiBB", 1, 1, 0, 2, 20) + string("default") + b"\x00"))
    else:
        conn.sendall(packet(IDS[0], struct.pack(">iBbBB", 1, 1, 0, 2, 20) + string("default") + b"\x00"))
    time.sleep(0.5)

    open_table(conn, 1)
    send_table_burst(conn, 1)
    time.sleep(0.3)
    send_table_burst(conn, 1)  # identical burst, the plugin must not forward it again
    time.sleep(0.3)
    conn.sendall(packet(IDS[4], struct.pack(">B", 1)))  # close window
    time.sleep(0.2)
    open_table(conn, 2)
    send_table_burst(conn, 2)
    time.sleep(1.5)
    conn.close()


def custom_payload_1_7(channel, data):
    return packet(0x17, string(channel) + struct.pack(">h", len(data)) + data)


def announce(client):
    if HELLO:
        client.sendall(custom_payload_1_7("ViaEnchant", b"\x01"))
    if REGISTER:
        client.sendall(custom_payload_1_7("REGISTER", b"FML|HS\x00FML\x00ViaEnchant"))


def read_custom_payload(data):
    length = data[0]  # 1.7 string: short length prefix
    # 1.7 strings are a varint length (<128 here) followed by UTF-8
    channel = data[1:1 + length].decode()
    rest = data[1 + length:]
    size = struct.unpack(">h", rest[:2])[0]
    return channel, rest[2:2 + size]


def main():
    workdir = tempfile.mkdtemp(prefix="vp-e2e-")
    os.makedirs(os.path.join(workdir, "plugins", "ViaEnchant"))
    shutil.copy(PLUGIN_JAR, os.path.join(workdir, "plugins"))
    with open(os.path.join(workdir, "plugins", "ViaEnchant", "config.properties"), "w") as config:
        config.write("chat-fallback=%s\n" % ("true" if CHAT_MODE else "auto" if AUTO_MODE else "false"))

    ready = threading.Event()
    threading.Thread(target=mock_server, args=(ready,), daemon=True).start()
    ready.wait()

    log = open(os.path.join(workdir, "viaproxy.log"), "w")
    proxy = subprocess.Popen(
        ["java", "-jar", VIAPROXY_JAR, "cli", "--bind-address", "127.0.0.1:%d" % PROXY_PORT,
         "--target-address", "127.0.0.1:%d" % SERVER_PORT, "--target-version", "1.12.2" if SERVER_112 else "1.8.x",
         "--auth-method", "NONE", "--chat-signing", "false"],
        cwd=workdir, stdout=log, stderr=subprocess.STDOUT)
    try:
        client = None
        for _ in range(120):
            try:
                client = socket.create_connection(("127.0.0.1", PROXY_PORT), timeout=1)
                break
            except OSError:
                time.sleep(0.5)
        if client is None:
            raise SystemExit("ViaProxy did not start, see %s" % log.name)

        client.settimeout(5)
        client.sendall(packet(0x00, varint(47 if CLIENT_18 else 5) + string("127.0.0.1") + struct.pack(">H", PROXY_PORT) + varint(2)))
        client.sendall(packet(0x00, string("Tester")))

        payloads, chats, ids = [], [], []
        compressed = False
        try:
            while True:
                packet_id, data = read_packet(client, compressed)
                ids.append(packet_id)
                if CLIENT_18 and packet_id == 0x03 and not compressed:
                    compressed = True  # set compression in the login state
                if packet_id == 0x3F:
                    channel, body = read_custom_payload(data)
                    if channel == "ViaEnchant":
                        payloads.append(struct.unpack(">Bhh", body) if not CLIENT_18 else ("1.8 client got a payload",))
                elif packet_id == 0x02:
                    chats.append(data.decode(errors="replace"))
                if packet_id == 0x01 and not CLIENT_18 and (HELLO or REGISTER):
                    announce(client)
        except (EOFError, socket.timeout, ConnectionError):
            pass

        print("packet ids seen:", sorted(set(ids)))
        if CLIENT_18 and 0x01 not in ids:
            print("FAIL: the 1.8 client never got the join game packet")
            sys.exit(1)
        print("ViaEnchant payloads (window, property, value):")
        for item in payloads:
            print("  ", item)
        for line in chats:
            print("chat:", line)

        chats = [c for c in chats if "[Enchant]" in c]
        if "ViaEnchant" in SERVER_SAW:
            print("FAIL: the server received the mod announcement")
            sys.exit(1)
        if CLIENT_18:
            if payloads or chats:
                print("FAIL: a 1.8 client must not get payloads or chat:", payloads, chats)
                sys.exit(1)
            print("PASS")
            return
        clue_values = [1234, 32 | (3 << 8), 16 | (2 << 8), -1]
        expected = [(1, p, v) for p, v in zip(range(3, 7), clue_values)]
        expected += [(2, p, v) for p, v in zip(range(3, 7), clue_values)]
        if payloads != expected:
            print("FAIL: unexpected payloads, expected:")
            for item in expected:
                print("  ", item)
            print("proxy log:", log.name)
            sys.exit(1)
        if EXPECT_CHAT:
            text = "\n".join(chats)
            if text.count("Efficiency III") != 2 or text.count("Sharpness II") != 2 or "Lvl 30" in text:
                print("FAIL: expected an Efficiency III and a Sharpness II line per window and none for the clue-less slot")
                sys.exit(1)
        elif chats:
            print("FAIL: unexpected chat lines:", chats)
            sys.exit(1)
        print("PASS")
    finally:
        proxy.terminate()
        try:
            proxy.wait(10)
        except subprocess.TimeoutExpired:
            proxy.kill()
        log.close()


main()
