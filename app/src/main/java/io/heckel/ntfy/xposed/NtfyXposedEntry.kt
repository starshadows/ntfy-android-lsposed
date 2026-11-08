package io.heckel.ntfy.xposed

import android.app.Application
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.heckel.ntfy.util.Log

@Keep
class NtfyXposedEntry : XposedModule() {
    companion object {
        const val TAG = "NtfyXposedEntry"
        lateinit var instance: NtfyXposedEntry
            private set
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        Log.i(TAG, "onModuleLoaded: process=${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val packageName = param.packageName
        Log.i(TAG, "onPackageLoaded: $packageName")

        when (packageName) {
            NtfyBridge.SYSTEM_UI_PACKAGE -> {
                hookSystemUI(param)
            }
            NtfyBridge.NTFY_PACKAGE -> {
                hookNtfyApp(param)
            }
        }
    }

    private fun hookSystemUI(param: XposedModuleInterface.PackageLoadedParam) {
        // 1. Permit cleartext HTTP traffic inside SystemUI
        try {
            val netSecPolicy = Class.forName("android.security.NetworkSecurityPolicy")
            netSecPolicy.getDeclaredMethod("isCleartextTrafficPermitted").apply {
                hook(this).intercept { true }
            }
            netSecPolicy.getDeclaredMethod("isCleartextTrafficPermitted", String::class.java).apply {
                hook(this).intercept { true }
            }
            Log.i(TAG, "NetworkSecurityPolicy cleartext permitted hook installed in SystemUI")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to hook NetworkSecurityPolicy in SystemUI: ${e.message}")
        }

        // 2. Hook Application.onCreate() to start SystemNtfyEngine
        try {
            val appClass = param.applicationInfo.className ?: "android.app.Application"
            val targetClass = param.defaultClassLoader.loadClass(appClass)
            val onCreateMethod = targetClass.getDeclaredMethod("onCreate")

            hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                if (app != null) {
                    Log.i(TAG, "SystemUI Application onCreate captured, initializing SystemNtfyEngine!")
                    try {
                        SystemNtfyEngine.getInstance(app).start()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to start SystemNtfyEngine", t)
                    }
                }
                result
            }
            Log.i(TAG, "SystemUI Application.onCreate hook installed on $appClass")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to hook ${param.packageName} application class, falling back to global Application", e)
            try {
                val fallbackMethod = Application::class.java.getDeclaredMethod("onCreate")
                hook(fallbackMethod).intercept { chain ->
                    val result = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null && app.packageName == NtfyBridge.SYSTEM_UI_PACKAGE) {
                        SystemNtfyEngine.getInstance(app).start()
                    }
                    result
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Critical: Global Application hook failed in SystemUI", t)
            }
        }
    }

    private fun hookNtfyApp(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val bridgeClass = param.defaultClassLoader.loadClass("io.heckel.ntfy.xposed.NtfyBridge")
            val field = bridgeClass.getDeclaredField("isXposedHookActive")
            field.isAccessible = true
            field.set(null, true)
            Log.i(TAG, "Successfully set isXposedHookActive=true in ntfy app ClassLoader")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set isXposedHookActive in app ClassLoader", t)
        }
        NtfyBridge.isXposedHookActive = true
        Log.i(TAG, "ntfy app loaded with LSPosed active")
    }
}
