package com.jn.dyparse

import android.util.Log

object NativeLib {
    init {
        try {
            System.loadLibrary("net_utils")
        } catch (e: Throwable) {
            Log.e("NativeLib", "Failed to load net_utils", e)
        }
    }

    @JvmStatic
    external fun getParserUserAgent(): String
    
    @JvmStatic
    external fun getApiUrlTemplate(type: Int): String
    
    @JvmStatic
    external fun getRegex(type: Int): String
    
    @JvmStatic
    external fun extractVideoId(url: String): String?
    
    @JvmStatic
    external fun doExtractJson(html: String): String?

    @JvmStatic
    external fun hasSuspiciousRuntime(): Boolean

    /**
     * native 侧运行时信号集合：
     * [0]=tracer, [1]=debugger(ptrace), [2]=suspicious_maps,
     * [3]=suspicious_port, [4]=injected_so
     */
    @JvmStatic
    external fun collectRuntimeSignals(): BooleanArray

    /** native 校验状态（Kotlin 无法伪造；被 hook 时返回 false） */
    @JvmStatic
    external fun isVerified(): Boolean
    
    @JvmStatic
    external fun getHistoryFileName(): String

    @JvmStatic
    external fun getRemoteAesKeyPart2(): ByteArray

    /**
     * 远端配置 AES-256 密钥（内存拼装，不落盘）。
     * 三片拆分：K1、K3 为 Kotlin base64 常量，K2 在 native 侧 XOR 混淆。
     * 完整密钥只在进程内临时存在。
     */
    @JvmStatic
    fun getRemoteAesKey(): ByteArray {
        val part1 = android.util.Base64.decode("d2CiJ8swYBtARyFB", android.util.Base64.DEFAULT)
        val part2 = getRemoteAesKeyPart2()
        val part3 = android.util.Base64.decode("JdRnCzVRJf9VmQ==", android.util.Base64.DEFAULT)
        val key = ByteArray(part1.size + part2.size + part3.size)
        System.arraycopy(part1, 0, key, 0, part1.size)
        System.arraycopy(part2, 0, key, part1.size, part2.size)
        System.arraycopy(part3, 0, key, part1.size + part2.size, part3.size)
        return key
    }
}
