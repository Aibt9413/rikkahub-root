package me.rerere.rikkahub.data.ai.root

import android.content.Context
import android.os.Process
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.ShellBackend
import java.io.File

/**
 * shell 执行调度：按设置里的后端选择 su / Shizuku / ADB。
 * 所有调用方必须先经过风险分级与确认闸门。
 */
class RootShellManager(private val context: Context) {

    data class Output(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    val shizuku = ShizukuBridge(context)
    val adb = AdbBridge(context)

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun exec(command: String, timeoutMs: Long = 30_000L): Output = withContext(Dispatchers.IO) {
        val seconds = (timeoutMs / 1000L).coerceIn(1L, 120L).toString()
        val result = runCatching {
            Shell.cmd("timeout", "-k", "2", seconds, "sh", "-c", command).exec()
        }.getOrElse { error ->
            return@withContext Output("", error.message ?: "exec failed", -1)
        }
        Output(result.out.joinToString("\n"), result.err.joinToString("\n"), result.code)
    }

    suspend fun execFor(backend: ShellBackend, command: String, timeoutMs: Long = 30_000L): Output =
        when (backend) {
            ShellBackend.ROOT -> exec(command, timeoutMs)
            ShellBackend.SHIZUKU -> shizuku.exec(command, timeoutMs)
            ShellBackend.ADB -> adb.exec(command, timeoutMs)
        }

    suspend fun readFile(path: String, maxBytes: Int = 256 * 1024): Output {
        val safeMax = maxBytes.coerceIn(1, 4 * 1024 * 1024)
        return exec("head -c $safeMax ${quote(path)}")
    }

    suspend fun readFileFor(backend: ShellBackend, path: String, maxBytes: Int = 256 * 1024): Output {
        val safeMax = maxBytes.coerceIn(1, 4 * 1024 * 1024)
        return execFor(backend, "head -c $safeMax ${quote(path)}")
    }

    suspend fun copyOut(source: String, destination: File): Output {
        destination.parentFile?.mkdirs()
        val uid = Process.myUid()
        val dest = quote(destination.absolutePath)
        return exec("cp -f ${quote(source)} $dest && chown $uid:$uid $dest && chmod 600 $dest")
    }

    /** 用 base64 传输，兼容所有后端（shell 身份不能 chown）。 */
    suspend fun copyOutFor(backend: ShellBackend, source: String, destination: File): Output {
        destination.parentFile?.mkdirs()
        val out = execFor(backend, "base64 ${quote(source)}", 120_000L)
        if (!out.ok) return out
        return runCatching {
            val bytes = java.util.Base64.getMimeDecoder().decode(out.stdout)
            destination.writeBytes(bytes)
            Output("已写入 ${bytes.size} 字节", "", 0)
        }.getOrElse { return Output("", it.message ?: "解码失败", -1) }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
