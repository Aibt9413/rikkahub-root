package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.RootReviewer
import me.rerere.rikkahub.data.ai.root.RootGate
import me.rerere.rikkahub.data.ai.root.RootRisk
import me.rerere.rikkahub.data.ai.root.RootRiskAssessor
import me.rerere.rikkahub.data.ai.root.RootShellManager
import me.rerere.rikkahub.data.datastore.RootPermissionSettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.RootTier
import java.io.File

private const val MAX_OUTPUT_CHARS = 64 * 1024

/**
 * root 工具集。只读工具不提供任意命令入口；写工具按设置档位注册。
 */
fun createRootTools(
    settings: Settings,
    shell: RootShellManager,
    gate: RootGate,
    reviewer: RootReviewer?,
    exportDir: File,
): List<Tool> {
    val root = settings.rootPermission
    if (!root.enabled || root.tier == RootTier.OFF) return emptyList()
    val tools = mutableListOf<Tool>()
    tools += rootListDirTool(root, shell, gate)
    tools += rootReadFileTool(root, shell, gate)
    tools += rootCopyOutTool(root, shell, gate, exportDir)
    if (root.tier != RootTier.READ_ONLY) {
        tools += rootShellTool(settings, root, shell, gate, reviewer)
    }
    return tools
}

private fun rootListDirTool(
    root: RootPermissionSettings,
    shell: RootShellManager,
    gate: RootGate,
) = Tool(
    name = "root_list_dir",
    description = "List a directory on the Android filesystem with root privileges (read-only). " +
        "Use this to inspect protected or app-private directories that normal tools cannot read.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to list")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = (args as? JsonObject)?.string("path") ?: return@Tool listOf(errorText("path 不能为空"))
        val command = "ls -la ${quote(path)}"
        val assessment = RootRiskAssessor.assess(command, root)
        if (assessment.protectedHit != null) {
            return@Tool listOf(errorText("已拒绝：命中保护区 ${assessment.protectedHit}"))
        }
        val decision = gate.request("root_list_dir", command, RootRisk.S0, assessment.reasons, assessment.protectedHit)
        if (!decision.allowed) return@Tool listOf(errorText(decision.reason))
        val out = shell.execFor(root.backend, command)
        listOf(UIMessagePart.Text(render(out)))
    },
)

private fun rootReadFileTool(
    root: RootPermissionSettings,
    shell: RootShellManager,
    gate: RootGate,
) = Tool(
    name = "root_read_file",
    description = "Read a file on the Android filesystem with root privileges (read-only, text output, truncated). " +
        "Binary files should be copied out with root_copy_out instead.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute file path")
                })
                put("max_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum bytes to read, default 262144")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val obj = args as? JsonObject ?: return@Tool listOf(errorText("参数错误"))
        val path = obj.string("path") ?: return@Tool listOf(errorText("path 不能为空"))
        val maxBytes = (obj["max_bytes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 262_144
        val command = "cat ${quote(path)}"
        val assessment = RootRiskAssessor.assess(command, root)
        if (assessment.protectedHit != null) {
            return@Tool listOf(errorText("已拒绝：命中保护区 ${assessment.protectedHit}"))
        }
        val decision = gate.request("root_read_file", command, RootRisk.S0, assessment.reasons, assessment.protectedHit)
        if (!decision.allowed) return@Tool listOf(errorText(decision.reason))
        val out = shell.readFileFor(root.backend, path, maxBytes)
        listOf(UIMessagePart.Text(render(out)))
    },
)

private fun rootCopyOutTool(
    root: RootPermissionSettings,
    shell: RootShellManager,
    gate: RootGate,
    exportDir: File,
) = Tool(
    name = "root_copy_out",
    description = "Copy a file from the root filesystem into the app's private sandbox for analysis. " +
        "Returns the destination path.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute source path on the Android filesystem")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional destination file name")
                })
            },
            required = listOf("source")
        )
    },
    execute = { args ->
        val obj = args as? JsonObject ?: return@Tool listOf(errorText("参数错误"))
        val source = obj.string("source") ?: return@Tool listOf(errorText("source 不能为空"))
        val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: source.substringAfterLast('/')
        val safeName = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(128).ifBlank { "export.bin" }
        val command = "cat ${quote(source)} > /dev/null"
        val assessment = RootRiskAssessor.assess(command, root)
        if (assessment.protectedHit != null) {
            return@Tool listOf(errorText("已拒绝：命中保护区 ${assessment.protectedHit}"))
        }
        val decision = gate.request("root_copy_out", command, RootRisk.S0, assessment.reasons, assessment.protectedHit)
        if (!decision.allowed) return@Tool listOf(errorText(decision.reason))
        val dest = File(exportDir, "${System.currentTimeMillis()}_$safeName")
        val out = shell.copyOutFor(root.backend, source, dest)
        if (!out.ok) return@Tool listOf(errorText("复制失败：${out.stderr.ifBlank { out.stdout }}"))
        listOf(UIMessagePart.Text("已复制到：${dest.absolutePath}"))
    },
)

private fun rootShellTool(
    settings: Settings,
    root: RootPermissionSettings,
    shell: RootShellManager,
    gate: RootGate,
    reviewer: RootReviewer?,
) = Tool(
    name = "root_shell",
    description = "Run a shell command as root. Requires the Advanced Permission (root) setting. " +
        "Dangerous commands are classified and require explicit confirmation; protected apps are always blocked.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to execute as root")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds, default 30000, max 120000")
                })
            },
            required = listOf("command")
        )
    },
    execute = { args ->
        val obj = args as? JsonObject ?: return@Tool listOf(errorText("参数错误"))
        val command = obj.string("command") ?: return@Tool listOf(errorText("command 不能为空"))
        val timeout = ((obj["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 30_000L)
            .coerceIn(1_000L, 120_000L)
        val assessment = RootRiskAssessor.assess(command, root)
        if (assessment.protectedHit != null) {
            return@Tool listOf(errorText("已拒绝：命中保护区 ${assessment.protectedHit}"))
        }
        var effectiveRisk = assessment.risk
        var reviewNote: String? = null
        if (root.autoReview && reviewer != null) {
            val review = reviewer.review(
                settings = settings,
                command = command,
                risk = assessment.risk,
            )
            if (review != null) {
                reviewNote = if (review.allow) "通过（${review.reason}）" else "拒绝（${review.reason}）"
                if (!review.allow) effectiveRisk = if (effectiveRisk < RootRisk.S3) RootRisk.S3 else effectiveRisk
            }
        }
        if (root.tier == RootTier.RESTRICTED_WRITE && effectiveRisk > RootRisk.S2) {
            return@Tool listOf(
                errorText("已拒绝：当前为“受限写入”档位，该命令被判定为 ${effectiveRisk}，请提升权限档位")
            )
        }
        val decision = if (root.allowPerTurn && effectiveRisk <= RootRisk.S2) {
            RootGate.Decision(true, autoApproved = true, reason = "本轮自动允许")
        } else {
            gate.request("root_shell", command, effectiveRisk, assessment.reasons, reviewNote = reviewNote)
        }
        if (!decision.allowed) return@Tool listOf(errorText(decision.reason))
        val out = shell.execFor(root.backend, command, timeout)
        listOf(UIMessagePart.Text(render(out)))
    },
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun render(out: RootShellManager.Output): String {
    val text = buildString {
        if (out.stdout.isNotBlank()) append(out.stdout)
        if (out.stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("[stderr]\n").append(out.stderr)
        }
        append("\n[exit ").append(out.exitCode).append(']')
    }
    return if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n…（输出已截断）" else text
}

private fun errorText(message: String) = UIMessagePart.Text("root 工具未执行：$message")
