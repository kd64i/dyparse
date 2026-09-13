package com.jn.dyparse

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Debug
import java.net.NetworkInterface
import java.util.Collections

object SecurityGuard {
    private const val ENFORCE_CACHE_MS = 30_000L
    private const val TRANSIENT_ENFORCE_CACHE_MS = 2_000L
    private const val INLINE_HOOK_CACHE_MS = 2_000L

    @Volatile
    private var lockedReason: String? = null

    @Volatile
    private var lastDetectionAtMs: Long = 0L

    @Volatile
    private var lastDetectionReason: String? = null

    @Volatile
    private var lastInlineHookCheckAtMs: Long = 0L

    @Volatile
    private var lastInlineHookDetected: Boolean = false

    private val suspiciousClasses = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XC_MethodHook",
        "de.robv.android.xposed.XposedHelpers",
        "de.robv.android.xposed.IXposedHookLoadPackage",
        "de.robv.android.xposed.callbacks.XC_LoadPackage",
        "de.robv.android.xposed.callbacks.XCallback",
        "org.lsposed.lspd.core.Main",
        "org.lsposed.lspd.impl.LSPosedBridge",
        "org.lsposed.hiddenapibypass.HiddenApiBypass",
        "com.elderdrivers.riru.edxp._hooker.impl.StartBootstrapServicesHooker",
        "com.elderdrivers.riru.edxp._hooker.impl.SystemMain",
        "com.swift.sandhook.SandHook",
        "com.swift.sandhook.SandHookConfig",
        "com.swift.sandhook.xposedcompat.XposedCompat",
        "me.weishu.epic.art.Epic",
        "me.weishu.epic.art.hook.HookManager",
        "me.weishu.exposed.ExposedBridge",
        "io.github.vvb2060.lsposed.HiddenApiBridge",
        "com.saurik.substrate.MS$2",
        "com.saurik.substrate.SubstrateClassLoader",
        "com.frida.Helper",
        "re.frida.Gadget",
        "com.close.hook.ads.hook.NetworkHook"
    )

    private val suspiciousTokens = listOf(
        "frida",
        "xposed",
        "lsposed",
        "zygisk",
        "riru",
        "adclose",
        "close.hook.ads",
        "sandhook",
        "substrate",
        "lsplant",
        "yahfa",
        "epic",
        "whale",
        "taichi",
        "xpatch",
        "pine",
        "edxp",
        "lspd"
    )

    fun enforce(context: Context): Boolean {
        val appContext = context.applicationContext

        lockedReason?.let {
            return false
        }

        val now = System.currentTimeMillis()
        val cachedReason = lastDetectionReason
        val cacheMs = if (cachedReason != null && !isPermanentLockReason(cachedReason)) {
            TRANSIENT_ENFORCE_CACHE_MS
        } else {
            ENFORCE_CACHE_MS
        }
        if (now - lastDetectionAtMs <= cacheMs) {
            lastDetectionReason?.let { reason ->
                if (isPermanentLockReason(reason)) {
                    lockedReason = reason
                }
                return false
            }
            return true
        }

        val reason = detect(appContext)
        lastDetectionAtMs = now
        lastDetectionReason = reason
        if (reason != null) {
            if (isPermanentLockReason(reason)) {
                lockedReason = reason
            }
            return false
        }

        return true
    }

    fun isLocked(): Boolean = lockedReason != null

    fun getLockedReason(): String? = lockedReason

    private fun isPermanentLockReason(reason: String): Boolean {
        return reason != "debugger" && reason != "proxy" && reason != "vpn"
    }

    fun hasInlineHookSignal(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastInlineHookCheckAtMs <= INLINE_HOOK_CACHE_MS) {
            return lastInlineHookDetected
        }

        val detected = hasSuspiciousClass() ||
            hasCurrentThreadHookStack() ||
            hasSuspiciousStacks() ||
            NativeLib.hasSuspiciousRuntime()
        lastInlineHookCheckAtMs = now
        lastInlineHookDetected = detected
        return detected
    }

    // ---- 供 SecurityReporter 上报的独立信号（不触发 enforce 锁）----

    fun hasProxySignal(): Boolean = hasManualProxy()

    fun hasVpnSignal(context: Context): Boolean = hasVpn(context)

    fun hasSuspiciousClassSignal(): Boolean = hasSuspiciousClass()

    fun hasSuspiciousStackSignal(): Boolean = hasSuspiciousStacks() || hasCurrentThreadHookStack()

    private fun detect(context: Context): String? {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            return "debugger"
        }

        if (hasManualProxy()) {
            return "proxy"
        }

        if (hasVpn(context)) {
            return "vpn"
        }

        if (hasSuspiciousClass()) {
            return "hook_class"
        }

        if (hasSuspiciousStacks()) {
            return "hook_stack"
        }

        if (NativeLib.hasSuspiciousRuntime()) {
            return "hook_runtime"
        }

        return null
    }

    private fun hasManualProxy(): Boolean {
        val httpHost = System.getProperty("http.proxyHost")
        val httpsHost = System.getProperty("https.proxyHost")
        return !httpHost.isNullOrBlank() || !httpsHost.isNullOrBlank()
    }

    private fun hasVpn(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return true
        }

        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { networkInterface ->
                val name = networkInterface.name.lowercase()
                networkInterface.isUp && (
                    name.startsWith("tun") ||
                        name.startsWith("ppp") ||
                        name.startsWith("ipsec") ||
                        name.startsWith("tap")
                    )
            }
        }.getOrDefault(false)
    }

    private fun hasSuspiciousClass(): Boolean {
        val loader = SecurityGuard::class.java.classLoader
        return suspiciousClasses.any { className ->
            runCatching { Class.forName(className, false, loader) }.isSuccess
        }
    }

    private fun hasSuspiciousStacks(): Boolean {
        return runCatching {
            Thread.getAllStackTraces().values.any { stack ->
                stack.any { element ->
                    val text = "${element.className}.${element.methodName}".lowercase()
                    suspiciousTokens.any(text::contains)
                }
            }
        }.getOrDefault(false)
    }

    private fun hasCurrentThreadHookStack(): Boolean {
        return Thread.currentThread().stackTrace.any { element ->
            val text = "${element.className}.${element.methodName}".lowercase()
            suspiciousTokens.any(text::contains)
        }
    }
}
