package org.windy.windui.server.ws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.windy.windui.core.ws.WsHandshake
import org.windy.windui.core.ws.WsProtocol
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WindUI 的 WebSocket 服务端（**平台无关**，放 server_core 供 velocity / bukkit 复用）。
 *
 * **为 10k+ 并发用协程 + 异步 NIO**：`AsynchronousServerSocketChannel` 接受连接，每连接一个协程（几 KB，
 * 不是平台线程的几百 KB~MB 栈），读帧在异步通道上挂起（等数据时不占线程），一小撮 IO 线程就能扛上万连接。
 * server_core 编到 Java 16 地板（无虚拟线程），协程是这里达成低内存高并发的关键。
 *
 * 帧编解码逻辑从原阻塞版 [org.windy.windui.core.ws.WsConnection] 忠实移植（服务端：入站帧必须带掩码、
 * 出站帧不加掩码）；握手复用已验证的 [WsHandshake]。公共 API 与旧 Java 版一致，平台 glue 与测试无需改动。
 */
class WsServer(
    private val bindHost: String?,
    private val port: Int,
    private val maxMessageBytes: Int,
    private val handler: Handler,
) {
    /** 平台注入的回调：鉴权 + 上行事件分派。 */
    interface Handler {
        /** 校验 token；通过返玩家 UUID，失败返 null（连接将被关闭）。 */
        fun authenticate(token: String): UUID?

        /** 客户端上报协议版本。 */
        fun onHello(player: UUID, version: Int)

        /** 界面事件回程（EventCodec 字节）。 */
        fun onEvent(player: UUID, eventBytes: ByteArray)

        /** 该玩家的 WS 连接断开。 */
        fun onDisconnect(player: UUID)
    }

    init {
        require(port in 0..65535) { "非法 WebSocket 端口: $port" }
        require(maxMessageBytes >= 64) { "maxMessageBytes 过小: $maxMessageBytes" }
    }

    private val sessions = ConcurrentHashMap<UUID, Conn>()

    // 所有连接协程挂在一个 supervisor scope 下（一个连接崩不影响别的）。IO 调度器承载挂起的读写。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var serverChannel: AsynchronousServerSocketChannel? = null
    @Volatile private var boundPort = 0
    @Volatile private var running = false

    /** 启动监听（非阻塞）。 */
    @Synchronized
    fun start() {
        if (running) return
        val address = if (bindHost.isNullOrEmpty()) InetSocketAddress(port)
        else InetSocketAddress(bindHost, port)
        val server = AsynchronousServerSocketChannel.open().bind(address, 128)
        serverChannel = server
        boundPort = (server.localAddress as InetSocketAddress).port
        running = true
        acceptLoop(server)
    }

    /** 关闭监听并断开所有会话。 */
    @Synchronized
    fun stop() {
        running = false
        serverChannel?.let { runCatching { it.close() } }
        serverChannel = null
        sessions.values.forEach { it.closeQuietly() }
        sessions.clear()
        boundPort = 0
        scope.cancel()
    }

    fun isConnected(player: UUID): Boolean {
        val conn = sessions[player]
        return conn != null && !conn.closed
    }

    /** 主动断开某玩家当前登记的 WebSocket 会话（玩家退服/切服时由平台调用）。 */
    fun disconnect(player: UUID?) {
        if (player == null) return
        sessions[player]?.let { dropSession(player, it) }
    }

    /** 实际绑定端口（配置为 0 自动选端口时返回真实端口；未启动前返回配置值）。 */
    fun port(): Int = if (boundPort > 0) boundPort else port

    /**
     * 给某玩家下发一条应用消息（channel 字节 + data）。无连接则静默丢弃。
     * 由平台线程调用：这里只构建帧并入队（非阻塞），实际异步写由该连接的写协程串行完成。
     */
    fun send(player: UUID?, channel: Byte, data: ByteArray?) {
        if (player == null || data == null) return
        val conn = sessions[player] ?: return
        val message = ByteArray(data.size + 1)
        message[0] = channel
        System.arraycopy(data, 0, message, 1, data.size)
        conn.enqueueWrite(buildFrame(OP_BINARY, message))
    }

    // ===== accept =====

    private fun acceptLoop(server: AsynchronousServerSocketChannel) {
        server.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Void?> {
            override fun completed(client: AsynchronousSocketChannel, attachment: Void?) {
                if (running) {
                    server.acceptNextSafely(this)
                    scope.launch { Conn(client).serve() }
                }
            }

            override fun failed(exc: Throwable, attachment: Void?) {
                if (running) server.acceptNextSafely(this)
            }
        })
    }

    private fun AsynchronousServerSocketChannel.acceptNextSafely(
        cb: CompletionHandler<AsynchronousSocketChannel, Void?>,
    ) {
        runCatching { accept(null, cb) }
    }

    private fun dropSession(player: UUID, conn: Conn) {
        if (sessions.remove(player, conn)) {
            conn.closeQuietly()
            runCatching { handler.onDisconnect(player) }
        }
    }

    // ===== 单个连接 =====

    /** 一帧（服务端读入）。 */
    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private inner class Conn(private val channel: AsynchronousSocketChannel) {
        @Volatile var closed = false
        private val writeMutex = Mutex()
        private val readBuf = ByteBuffer.allocate(READ_BUFFER)
        private var player: UUID? = null

        init {
            readBuf.limit(0) // 初始无可读数据
        }

        /** 一整条连接的生命周期：握手 → 鉴权 → 读循环。 */
        suspend fun serve() {
            try {
                if (!handshake()) return
                if (!authenticate()) return
                readLoop()
            } catch (_: CancellationException) {
                // 关停/断开
            } catch (_: Exception) {
                // 任何协议/IO 异常 → 关连接（隔离，不影响别的连接）
            } finally {
                player?.let { p -> if (sessions.remove(p, this)) runCatching { handler.onDisconnect(p) } }
                closeQuietly()
            }
        }

        // --- 握手 ---

        private suspend fun handshake(): Boolean {
            // 读到 \r\n\r\n 为止，再交给已验证的 WsHandshake 解析（复用，不重写 HTTP 解析）。
            val headerBytes = readHttpHeader() ?: return false
            val headers = WsHandshake.readRequest(ByteArrayInputStream(headerBytes)) ?: return false
            if (!isExpectedPath(headers[""])) return false
            val accept = WsHandshake.acceptKey(headers["sec-websocket-key"])
            val resp = ("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
            writeAll(ByteBuffer.wrap(resp.toByteArray(Charsets.US_ASCII)))
            return true
        }

        /** 读 HTTP 请求头字节（截止到空行）。含 buffer 内已预读的字节。 */
        private suspend fun readHttpHeader(): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            var matched = 0 // 匹配 \r\n\r\n 的进度
            while (out.size() < MAX_HANDSHAKE_BYTES) {
                val b = readOneByte() ?: return null
                out.write(b.toInt())
                matched = when {
                    (matched == 0 || matched == 2) && b.toInt() == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b.toInt() == '\n'.code -> matched + 1
                    else -> 0
                }
                if (matched == 4) return out.toByteArray()
            }
            return null // 头过大
        }

        // --- 鉴权：首帧必须 AUTH ---

        private suspend fun authenticate(): Boolean {
            val first = readMessage() ?: return false
            if (first.size < 2 || first[0] != WsProtocol.AUTH) return false
            val token = String(first, 1, first.size - 1, Charsets.UTF_8)
            if (token.isBlank()) return false
            val p = handler.authenticate(token) ?: return false
            player = p
            // 同玩家重连：新连接登记为权威会话，关闭旧连接。
            val old = sessions.put(p, this)
            if (old != null && old !== this) old.closeQuietly()
            return true
        }

        // --- 读循环 ---

        private suspend fun readLoop() {
            val p = player ?: return
            while (running && sessions[p] === this) {
                val message = readMessage() ?: break
                if (message.size < 1) continue
                dispatch(p, message)
            }
        }

        private fun dispatch(p: UUID, message: ByteArray) {
            when (message[0]) {
                WsProtocol.HELLO -> {
                    // [HELLO][version:2 big-endian]
                    if (message.size >= 3) {
                        val version = ((message[1].toInt() and 0xFF) shl 8) or (message[2].toInt() and 0xFF)
                        runCatching { handler.onHello(p, version) }
                    }
                }
                WsProtocol.EVENT -> {
                    val body = message.copyOfRange(1, message.size)
                    // 事件处理可能较重（脚本），丢到 scope 另跑，别卡本连接读循环。
                    scope.launch { runCatching { handler.onEvent(p, body) } }
                }
                else -> { /* 未知上行通道，忽略 */ }
            }
        }

        // --- WebSocket 消息（可能多帧分片）读取；返回 null=连接结束 ---

        private suspend fun readMessage(): ByteArray? {
            var assembled: java.io.ByteArrayOutputStream? = null
            while (true) {
                val frame = readFrame() ?: return null
                when {
                    frame.opcode == OP_PING -> enqueueWrite(buildFrame(OP_PONG, frame.payload))
                    frame.opcode == OP_PONG -> { /* 透明 */ }
                    frame.opcode == OP_CLOSE -> {
                        runCatching { writeAll(ByteBuffer.wrap(buildFrame(OP_CLOSE, ByteArray(0)))) }
                        return null
                    }
                    frame.opcode == OP_TEXT || frame.opcode == OP_BINARY -> {
                        if (assembled != null) throw IllegalStateException("上一条分片消息尚未结束时收到新数据帧")
                        if (frame.fin) return frame.payload
                        assembled = java.io.ByteArrayOutputStream(frame.payload.size.coerceAtLeast(32))
                        appendFragment(assembled, frame.payload)
                    }
                    frame.opcode == OP_CONT -> {
                        val acc = assembled ?: throw IllegalStateException("意外的 WebSocket 续帧")
                        appendFragment(acc, frame.payload)
                        if (frame.fin) return acc.toByteArray()
                    }
                    else -> throw IllegalStateException("未知 WebSocket opcode: ${frame.opcode}")
                }
            }
        }

        private fun appendFragment(acc: java.io.ByteArrayOutputStream, payload: ByteArray) {
            if (acc.size().toLong() + payload.size > maxMessageBytes) {
                throw IllegalStateException("WebSocket 分片消息过大")
            }
            acc.write(payload)
        }

        // --- 单帧读取（服务端：入站必须带掩码），移植自 WsConnection.readFrame ---

        private suspend fun readFrame(): Frame? {
            val b0 = readOneByte()?.toInt()?.and(0xFF) ?: return null
            if (b0 and 0x70 != 0) throw IllegalStateException("不支持带 RSV 扩展位的帧")
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val control = opcode >= 0x8

            val b1 = readByteChecked()
            val masked = b1 and 0x80 != 0
            if (!masked) throw IllegalStateException("客户端发往服务端的帧必须带掩码")

            var length: Long = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = (readByteChecked().toLong() shl 8) or readByteChecked().toLong()
                if (length < 126) throw IllegalStateException("非最短 16 位长度编码")
            } else if (length == 127L) {
                val firstByte = readByteChecked()
                if (firstByte and 0x80 != 0) throw IllegalStateException("64 位长度最高位不得为 1")
                length = firstByte.toLong()
                for (i in 1 until 8) length = (length shl 8) or readByteChecked().toLong()
                if (length <= 0xFFFFL) throw IllegalStateException("非最短 64 位长度编码")
            }

            if (control) {
                if (!fin) throw IllegalStateException("控制帧不得分片")
                if (length > 125) throw IllegalStateException("控制帧负载过大: $length")
                if (opcode == OP_CLOSE && length == 1L) throw IllegalStateException("Close 帧负载长度不得为 1")
            }
            if (length > maxMessageBytes) throw IllegalStateException("帧过大: $length")

            val mask = readExact(4)
            val payload = readExact(length.toInt())
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            return Frame(fin, opcode, payload)
        }

        private suspend fun readByteChecked(): Int = (readOneByte() ?: throw IllegalStateException("流意外结束")).toInt() and 0xFF

        // --- 底层：带缓冲的异步读 ---

        /** 读恰好 n 字节（不足则异步续读）。 */
        private suspend fun readExact(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                if (!readBuf.hasRemaining() && !fillBuffer()) throw IllegalStateException("流意外结束")
                val take = minOf(n - got, readBuf.remaining())
                readBuf.get(out, got, take)
                got += take
            }
            return out
        }

        private suspend fun readOneByte(): Byte? {
            if (!readBuf.hasRemaining() && !fillBuffer()) return null
            return readBuf.get()
        }

        /** 异步把更多字节读进 readBuf；返回 false = 对端关闭。 */
        private suspend fun fillBuffer(): Boolean {
            readBuf.clear()
            val n = channel.readSuspend(readBuf)
            readBuf.flip()
            return n > 0
        }

        // --- 写：多线程 send 入队，写协程串行异步写 ---

        fun enqueueWrite(frame: ByteArray) {
            if (closed) return
            scope.launch {
                try {
                    writeMutex.withLock { writeAll(ByteBuffer.wrap(frame)) }
                } catch (_: Exception) {
                    player?.let { dropSession(it, this@Conn) }
                }
            }
        }

        private suspend fun writeAll(buf: ByteBuffer) {
            while (buf.hasRemaining()) {
                val n = channel.writeSuspend(buf)
                if (n < 0) throw IllegalStateException("写入时流结束")
            }
        }

        fun closeQuietly() {
            closed = true
            runCatching { channel.close() }
        }
    }

    // ===== 帧构建（服务端出站不加掩码），移植自 WsConnection.writeFrameLocked =====

    private fun buildFrame(opcode: Int, payload: ByteArray): ByteArray {
        val length = payload.size
        val header = java.io.ByteArrayOutputStream(10)
        header.write(0x80 or opcode)
        when {
            length < 126 -> header.write(length)
            length <= 0xFFFF -> {
                header.write(126)
                header.write((length ushr 8) and 0xFF)
                header.write(length and 0xFF)
            }
            else -> {
                header.write(127)
                for (i in 7 downTo 0) header.write(((length.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        val h = header.toByteArray()
        val out = ByteArray(h.size + length)
        System.arraycopy(h, 0, out, 0, h.size)
        System.arraycopy(payload, 0, out, h.size, length)
        return out
    }

    private fun isExpectedPath(requestLine: String?): Boolean {
        // "GET /windui HTTP/1.1"
        return requestLine != null && requestLine.startsWith("GET " + WS_PATH)
    }

    companion object {
        private const val READ_BUFFER = 16 * 1024
        private const val MAX_HANDSHAKE_BYTES = 32 * 1024

        /** WindUI 固定 WebSocket 路径（与客户端 WsClient 一致）。 */
        private const val WS_PATH = "/windui"

        // WebSocket 帧 opcode（RFC 6455）。与 core 的 WsConnection 私有常量一致。
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
    }
}

// ===== AsynchronousSocketChannel 的挂起包装（回调 → 协程恢复），供本文件使用 =====

private suspend fun AsynchronousSocketChannel.readSuspend(buf: ByteBuffer): Int =
    suspendCancellableCoroutine { cont ->
        read(buf, null, object : CompletionHandler<Int, Void?> {
            override fun completed(result: Int, attachment: Void?) = cont.resume(result)
            override fun failed(exc: Throwable, attachment: Void?) {
                if (exc is CancellationException) cont.resumeWithException(exc) else cont.resumeWithException(exc)
            }
        })
    }

private suspend fun AsynchronousSocketChannel.writeSuspend(buf: ByteBuffer): Int =
    suspendCancellableCoroutine { cont ->
        write(buf, null, object : CompletionHandler<Int, Void?> {
            override fun completed(result: Int, attachment: Void?) = cont.resume(result)
            override fun failed(exc: Throwable, attachment: Void?) = cont.resumeWithException(exc)
        })
    }
