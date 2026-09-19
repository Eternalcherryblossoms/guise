package io.guise.app.service

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge to the Xposed framework.
 *
 * The modern API removed self-hooking: a module app is no longer injected into itself, so
 * the old trick of having the app hook its own `isXposedWork()` method is gone. Instead the
 * framework pushes an [XposedService] binder into the app, which is both how the app knows
 * a framework is present and how it persists configuration.
 *
 * Writes must go through here. Remote preferences are read-only inside hooked processes;
 * only the service side can put values.
 */
object XposedBridgeClient {

    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service.asStateFlow()

    val isConnected: Boolean get() = _service.value != null

    @Volatile
    private var initialised = false

    fun init() {
        if (initialised) return
        initialised = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                _service.value = service
            }

            override fun onServiceDied(service: XposedService) {
                if (_service.value === service) _service.value = null
            }
        })
    }

    /** Framework description for the status banner, or null when nothing is bound. */
    fun describe(): String? = _service.value?.let { svc ->
        runCatching {
            "${svc.frameworkName} ${svc.frameworkVersion} (service api ${svc.apiVersion})"
        }.getOrNull()
    }

    /**
     * Ask the framework to add [packageName] to this module's scope.
     *
     * This is what replaces a static `scope.list`: the user picks a target in the UI and the
     * scope grows to match, instead of LSPosed pre-selecting apps that were never configured.
     */
    fun requestScope(packageName: String, onResult: (Result<List<String>>) -> Unit) {
        val svc = _service.value
        if (svc == null) {
            onResult(Result.failure(IllegalStateException("Xposed 框架未连接")))
            return
        }
        runCatching {
            svc.requestScope(listOf(packageName), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    onResult(Result.success(approved))
                }

                override fun onScopeRequestFailed(message: String) {
                    onResult(Result.failure(IllegalStateException(message)))
                }
            })
        }.onFailure { onResult(Result.failure(it)) }
    }

    fun currentScope(): List<String> =
        runCatching { _service.value?.scope.orEmpty() }.getOrDefault(emptyList())
}
