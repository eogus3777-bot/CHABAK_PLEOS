package com.chabak.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-owned lifecycle/transport coordination, not a Pleos SDK class. */
internal class GleoCommandSession(
    private val context: Context,
    private val timeoutMillis: Long = 6_000L
) {
    class Request internal constructor(
        val intent: Intent,
        internal val fingerprint: String?,
        internal val deadline: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val active = MutableStateFlow<Request?>(null)
    val requests = active.asStateFlow()
    private var timeout: Runnable? = null

    @Synchronized
    fun submit(intent: Intent) {
        if (intent.action !in GleoContract.actions) return
        val fingerprint = GleoContract.fingerprint(intent)
        active.value?.let { current ->
            if (fingerprint != null && current.fingerprint == fingerprint) {
                Log.i("ChabakGleo", "${intent.action} duplicate_in_flight")
            } else {
                GleoContract.reply(context, intent.action!!, GleoResponse("fail",
                    "Another CHABAK voice command is still running. Please try again.", "REQUEST_IN_PROGRESS"))
            }
            return
        }
        val request = Request(Intent(intent), fingerprint, SystemClock.uptimeMillis() + timeoutMillis)
        active.value = request
        timeout = Runnable { complete(request, timedOut()) }.also {
            handler.postAtTime(it, request.deadline)
        }
    }

    @Synchronized
    fun canExecute(request: Request): Boolean {
        if (active.value !== request) return false
        if (SystemClock.uptimeMillis() >= request.deadline) {
            complete(request, timedOut())
            return false
        }
        return true
    }

    @Synchronized
    fun complete(request: Request, response: GleoResponse) {
        if (active.value !== request) return // Late, repeated and replaced callbacks cannot report success.
        timeout?.let(handler::removeCallbacks)
        timeout = null
        active.value = null
        GleoContract.reply(context, request.intent.action!!,
            if (SystemClock.uptimeMillis() >= request.deadline) timedOut() else response)
    }

    @Synchronized
    fun close() {
        active.value?.let { complete(it, GleoResponse("fail",
            "CHABAK closed before the command completed.", "APP_CLOSED")) }
    }

    private fun timedOut() = GleoResponse("fail",
        "CHABAK could not complete the command within the response time limit.", "TIMEOUT")
}

// Keep the pending command and its deadline across Activity recreation; never retain an Activity.
internal class GleoCommandModel(application: Application) : AndroidViewModel(application) {
    val session = GleoCommandSession(application)
    var initialIntentReceived = false
    override fun onCleared() { session.close() }
}
