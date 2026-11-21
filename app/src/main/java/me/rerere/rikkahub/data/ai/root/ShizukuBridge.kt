package me.rerere.rikkahub.data.ai.root

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.IShizukuShell
import me.rerere.rikkahub.service.ShizukuShellService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 后端：把命令交给运行在 Shizuku 进程里的 UserService 执行。
 * Shizuku 由 ADB 启动时 uid=2000；由 root 启动时 uid=0。
 */
class ShizukuBridge(private val context: Context) {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context, ShizukuShellService::class.java)
    )
        .daemon(false)
        .processNameSuffix("shizuku_shell")
        .version(1)
        .tag("rikkahub-shizuku-shell")

    @Volatile
    private var remote: IShizukuShell? = null

    fun installed(): Boolean = runCatching {
        context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (!running()) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    suspend fun awaitPermission(timeoutMs: Long = 15_000L): Boolean = withContext(Dispatchers.Default) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (granted()) return@withContext true
            delay(300)
        }
        false
    }

    suspend fun exec(command: String, timeoutMs: Long): RootShellManager.Output = withContext(Dispatchers.Default) {
        if (!granted()) return@withContext RootShellManager.Output("", "Shizuku 未授权", -1)
        val service = remote ?: connect() ?: return@withContext RootShellManager.Output("", "Shizuku 服务连接失败", -1)
        val raw = runCatching {
            service.exec(command, timeoutMs.toInt().coerceIn(1_000, 120_000))
        }.getOrElse { return@withContext RootShellManager.Output("", it.message ?: "Shizuku 执行失败", -1) }
        parse(raw)
    }

    private suspend fun connect(): IShizukuShell? = withContext(Dispatchers.Main) {
        remote?.let { return@withContext it }
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
            }
        }
        val bound = runCatching {
            Shizuku.bindUserService(args, connection)
            true
        }.getOrDefault(false)
        if (!bound) return@withContext null
        val ok = withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }
        if (!ok || binder == null) return@withContext null
        IShizukuShell.Stub.asInterface(binder).also { remote = it }
    }

    private fun parse(raw: String): RootShellManager.Output {
        val firstLine = raw.lineSequence().firstOrNull().orEmpty()
        val code = firstLine.removePrefix("exit:").toIntOrNull() ?: -1
        val body = raw.substringAfter('\n', "")
        return RootShellManager.Output(stdout = body, stderr = "", exitCode = code)
    }

    private companion object {
        const val REQUEST_CODE = 4210
    }
}
