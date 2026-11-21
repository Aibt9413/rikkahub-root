package me.rerere.rikkahub.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku 进程里的 shell 服务（uid 2000，或 Shizuku 以 root 启动时的 uid 0）。
 * 只接受单条命令，输出合并 stdout/stderr，返回 "exit:<code>\n<output>"。
 */
class ShizukuShellService : Service() {
    private val binder = object : IShizukuShell.Stub() {
        override fun exec(command: String, timeoutMs: Int): String {
            return try {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(timeoutMs.toLong().coerceIn(1_000L, 120_000L), TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    "exit:-1\n[timeout]"
                } else {
                    val output = ByteArrayOutputStream().use { buffer ->
                        process.inputStream.copyTo(buffer)
                        buffer.toString(Charsets.UTF_8.name())
                    }
                    "exit:${process.exitValue()}\n$output"
                }
            } catch (e: Exception) {
                "exit:-1\n${e.message ?: "exec failed"}"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
