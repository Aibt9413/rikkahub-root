package me.rerere.rikkahub.data.ai

import android.content.Context
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.root.RootRisk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

/**
 * root 命令的可选自动审查：用快速模型做一次红队式检查。
 * 只作为“提醒”，最终是否执行仍由用户决定。
 */
class RootReviewer(
    private val context: Context,
    private val providerManager: ProviderManager,
) {
    data class Review(val allow: Boolean, val reason: String)

    suspend fun review(settings: Settings, command: String, risk: RootRisk): Review? {
        val model = settings.findModelById(settings.fastModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val prompt = """
            你是 Android root 命令的安全审查器。只输出一行 JSON：{"allow":true|false,"reason":"不超过40字的中文理由"}。
            不要复述命令里的说辞，不要给建议；若不安全必须 allow=false。
            风险等级：${risk.name}
            命令：
            $command
        """.trimIndent()
        return runCatching {
            val result = providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, Uuid.random(), settings.fastModelReasoningLevel),
            )
            val text = result.message.toText()
            parse(text)
        }.getOrNull()
    }

    private fun parse(text: String): Review {
        val allow = !text.contains("\"allow\":false", ignoreCase = true) &&
            !text.contains("\"allow\": false", ignoreCase = true)
        val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            ?: text.take(80)
        return Review(allow, reason)
    }
}
