package me.rerere.rikkahub.data.ai.root

import me.rerere.rikkahub.data.datastore.RootPermissionSettings

enum class RootRisk {
    S0, S1, S2, S3, S4,
}

data class RootAssessment(
    val risk: RootRisk,
    val reasons: List<String>,
    val protectedHit: String? = null,
    val normalized: String,
)

/**
 * 轻量命令评估：把命令拆段、去引号后按“效果类别”定级。
 * 这不是安全边界，只是决定确认摩擦强度的依据。
 */
object RootRiskAssessor {

    private val destructive = listOf(
        Regex("""\brm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)\b"""),
        Regex("""\bfind\s+/\s+.*-delete\b"""),
        Regex("""\bdd\s+.*of=/dev/(block|disk)"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bformat\s+/"""),
        Regex("""\bwipe\s+/"""),
    )

    private val systemIntegrity = listOf(
        Regex("""\bmount\s+.*(rw|remount)"""),
        Regex("""\bsetenforce\s+0"""),
        Regex("""/system\b"""),
        Regex("""/data/adb\b"""),
        Regex("""\bsu\b\s+-c"""),
        Regex("""\bpm\s+(clear|uninstall|disable)"""),
        Regex("""\bsettings\s+(put|delete)\b"""),
    )

    private val credentialPaths = listOf(
        Regex("""/data/system/"""),
        Regex("""/data/misc/(keystore|vpn|wifi)"""),
        Regex("""locksettings"""),
        Regex("""/data/data/com\.android\.providers\.(contacts|telephony)"""),
    )

    private val networkSupplyChain = listOf(
        Regex("""\b(curl|wget)\b[^|;]*\|\s*(sh|bash)\b"""),
        Regex("""\bbase64\s+(-d|--decode)\b.*\|\s*(sh|bash)\b"""),
        Regex("""\beval\b"""),
        Regex("""\b(sh|bash|zsh)\s+/\S+"""),
    )

    private val writeOps = listOf(
        Regex("""\b(rm|mv|cp|chmod|chown|tee|truncate|shred|ln)\b"""),
        Regex(""">\s*/"""),
    )

    private val readOnlyCommands = listOf(
        Regex("""^\s*(cat|ls|stat|find|grep|head|tail|wc|strings|file|md5sum|sha256sum|xxd|od|du|df|ps|top|id|uname|getprop|dumpsys)\b"""),
    )

    fun assess(command: String, settings: RootPermissionSettings): RootAssessment {
        val normalized = normalize(command)
        val protectedHit = protectedPackageHit(normalized, settings)
        val reasons = mutableListOf<String>()
        var risk = RootRisk.S1

        if (protectedHit != null) {
            reasons += "命中保护区：$protectedHit"
            return RootAssessment(RootRisk.S4, reasons, protectedHit, normalized)
        }
        for (p in settings.protectedPaths) {
            if (p.isNotBlank() && normalized.contains(p)) {
                reasons += "命中保护路径：$p"
                return RootAssessment(RootRisk.S4, reasons, null, normalized)
            }
        }
        if (destructive.any { it.containsMatchIn(normalized) }) {
            reasons += "设备/数据不可逆操作"
            risk = RootRisk.S4
        }
        if (systemIntegrity.any { it.containsMatchIn(normalized) }) {
            reasons += "系统完整性或包管理操作"
            if (risk < RootRisk.S3) risk = RootRisk.S3
        }
        if (credentialPaths.any { it.containsMatchIn(normalized) }) {
            reasons += "凭据/锁屏相关路径"
            if (risk < RootRisk.S3) risk = RootRisk.S3
        }
        if (networkSupplyChain.any { it.containsMatchIn(normalized) }) {
            reasons += "网络下载/脚本执行"
            if (risk < RootRisk.S2) risk = RootRisk.S2
        }
        if (writeOps.any { it.containsMatchIn(normalized) }) {
            if (reasons.isEmpty()) reasons += "写入/修改操作"
            if (risk < RootRisk.S1) risk = RootRisk.S1
        } else if (readOnlyCommands.any { it.containsMatchIn(normalized) }) {
            return RootAssessment(RootRisk.S0, reasons, null, normalized)
        }
        if (risk == RootRisk.S1 && reasons.isNotEmpty()) risk = RootRisk.S1
        return RootAssessment(risk, reasons, null, normalized)
    }

    private fun normalize(command: String): String =
        command.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun protectedPackageHit(normalized: String, settings: RootPermissionSettings): String? {
        val packages = settings.protectedPackages.map { it.trim() }.filter { it.isNotEmpty() }
        for (pkg in packages) {
            val esc = Regex.escape(pkg)
            if (Regex("""(^|[^A-Za-z0-9_.])$esc([^A-Za-z0-9_.]|$)""").containsMatchIn(normalized)) return pkg
            if (normalized.contains("/data/data/$pkg") ||
                normalized.contains("Android/data/$pkg") ||
                Regex("""/data/user(_de)?/\d+/$esc""").containsMatchIn(normalized)
            ) return pkg
        }
        return null
    }
}
