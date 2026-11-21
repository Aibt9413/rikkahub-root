package me.rerere.rikkahub.data.ai.root

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 无线调试（ADB）后端：用本地配对得到的密钥以 shell 身份（uid 2000）执行命令。
 * 需要用户开启开发者选项里的“无线调试”并输入配对码。
 */
class AdbBridge(private val context: Context) {

    private val key: PrivateKey by lazy { loadPrivateKey() }
    private val cert: Certificate by lazy { loadCertificate() }
    private val pairPort = AtomicInteger(0)
    private val connectPort = AtomicInteger(0)
    private val pairedFlag = File(context.filesDir, "adb/paired")

    private val manager = object : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(8, TimeUnit.SECONDS)
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "rikkahub-adb"
    }

    fun paired(): Boolean = pairedFlag.isFile

    fun connected(): Boolean = runCatching { manager.isConnected }.getOrDefault(false)

    fun statusText(): String = when {
        connected() -> "ADB 已连接（shell）"
        paired() -> "ADB 已配对，未连接"
        else -> "ADB 未配对"
    }

    suspend fun pair(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(code.matches(Regex("[0-9]{6}"))) { "配对码必须是 6 位数字" }
            val ports = discoverPorts(needPair = true)
            val port = ports.first ?: error("未发现配对端口，请确认无线调试已开启")
            check(manager.pair("127.0.0.1", port, code)) { "配对失败，请检查配对码" }
            pairedFlag.parentFile?.mkdirs()
            pairedFlag.writeText("ok")
        }
    }

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(paired()) { "请先配对" }
            val ports = discoverPorts(needPair = false)
            val port = ports.second ?: error("未发现连接端口")
            check(manager.connect("127.0.0.1", port)) { "连接失败" }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { manager.disconnect() }
        Unit
    }

    suspend fun exec(command: String, timeoutMs: Long): RootShellManager.Output = withContext(Dispatchers.IO) {
        if (!connected()) {
            val connectResult = connect()
            if (connectResult.isFailure) {
                return@withContext RootShellManager.Output(
                    "", connectResult.exceptionOrNull()?.message ?: "ADB 未连接", -1
                )
            }
        }
        val stream = runCatching { manager.openStream("shell:$command") }.getOrElse {
            return@withContext RootShellManager.Output("", it.message ?: "ADB 执行失败", -1)
        }
        val output = ByteArrayOutputStream()
        val ok = withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 120_000L)) {
            runCatching {
                stream.openInputStream().use { input -> input.copyTo(output) }
                true
            }.getOrDefault(false)
        } ?: false
        runCatching { stream.close() }
        val text = output.toString(Charsets.UTF_8.name())
        if (!ok) RootShellManager.Output(text, "ADB 执行超时", -1)
        else RootShellManager.Output(text, "", 0)
    }

    private fun discoverPorts(needPair: Boolean): Pair<Int?, Int?> {
        pairPort.set(0)
        connectPort.set(0)
        val pairMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            if (port in 1..65535) pairPort.set(port)
        }
        val connectMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { _, port ->
            if (port in 1..65535) connectPort.set(port)
        }
        pairMdns.start()
        connectMdns.start()
        val deadline = System.currentTimeMillis() + 8_000
        try {
            while (System.currentTimeMillis() < deadline) {
                val p = pairPort.get().takeIf { it > 0 }
                val c = connectPort.get().takeIf { it > 0 }
                if ((needPair && p != null) || (!needPair && c != null)) {
                    return p to c
                }
                Thread.sleep(120)
            }
        } finally {
            pairMdns.stop()
            connectMdns.stop()
        }
        return pairPort.get().takeIf { it > 0 } to connectPort.get().takeIf { it > 0 }
    }

    private fun loadPrivateKey(): PrivateKey {
        val pem = context.resources.openRawResource(R.raw.adb_key).use { it.readBytes() }
        val base64 = pem.toString(Charsets.UTF_8)
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val der = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun loadCertificate(): Certificate {
        val pem = context.resources.openRawResource(R.raw.adb_cert).use { it.readBytes() }
        return CertificateFactory.getInstance("X.509").generateCertificate(pem.inputStream())
    }
}
