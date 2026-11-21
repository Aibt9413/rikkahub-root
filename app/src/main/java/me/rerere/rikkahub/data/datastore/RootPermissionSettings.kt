package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/** root 权限档位。顺序即能力大小，比较时可直接用 ordinal。 */
/** shell 执行后端。ROOT = su；SHIZUKU = Shizuku 用户服务；ADB = 无线调试。 */
@Serializable
enum class ShellBackend { ROOT, SHIZUKU, ADB }

@Serializable
enum class RootTier {
    OFF,
    READ_ONLY,
    RESTRICTED_WRITE,
    FULL,
}

/**
 * 高级权限（root）设置。
 *
 * 默认全部关闭；开启后每次 root 工具调用仍会经过确认或自动审查。
 * [protectedPackages] 命中的路径/包名会被硬拒绝，不进入确认流程。
 */
@Serializable
data class RootPermissionSettings(
    val enabled: Boolean = false,
    val tier: RootTier = RootTier.OFF,
    val backend: ShellBackend = ShellBackend.ROOT,
    /** 每次 root 调用都弹确认（默认开启） */
    val confirmEveryRequest: Boolean = true,
    /** 本轮对话内自动允许低风险操作（S0-S2），默认关闭 */
    val allowPerTurn: Boolean = false,
    /** 可选的 AI 自动审查（实验性，消耗额外 token） */
    val autoReview: Boolean = false,
    /** 硬禁止触碰的应用包名 */
    val protectedPackages: List<String> = DEFAULT_PROTECTED_PACKAGES,
    /** 额外保护路径，例如自定义的敏感目录 */
    val protectedPaths: List<String> = DEFAULT_PROTECTED_PATHS,
) {
    companion object {
        val DEFAULT_PROTECTED_PACKAGES: List<String> = listOf(
            // 通讯
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.wework",
            "com.alibaba.android.rimet",
            // 支付
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            // 银行
            "com.icbc",
            "com.chinamworld.main",
            "com.android.bankabc",
            "com.chinamworld.bocmbci",
            "com.bankcomm.Bankcomm",
            "cmb.pb",
            "com.yitong.mbank.psbc",
            "com.cmbchina.ccd.pluto.cmbActivity",
        )

        val DEFAULT_PROTECTED_PATHS: List<String> = listOf(
            "/data/adb",
            "/data/system",
            "/data/misc/keystore",
        )
    }
}
