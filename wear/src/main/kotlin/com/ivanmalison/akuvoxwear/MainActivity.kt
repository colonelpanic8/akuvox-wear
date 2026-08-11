package com.ivanmalison.akuvoxwear

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.ivanmalison.akuvoxwear.protocol.WireProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private var state by mutableStateOf<UnlockState>(UnlockState.Idle)
    private var pendingRequestId: String? = null
    private var timeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Button(
                        enabled = state !is UnlockState.Sending,
                        onClick = ::requestUnlock,
                        modifier = Modifier.size(124.dp),
                    ) {
                        Text(
                            text = if (state is UnlockState.Sending) "Sending…" else "Unlock",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    Text(
                        text = state.message,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onStop() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onStop()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WireProtocol.UNLOCK_RESULT_PATH) return
        val result = runCatching { WireProtocol.decodeResult(messageEvent.data) }.getOrNull() ?: return
        if (result.requestId != pendingRequestId) return
        runOnUiThread {
            timeoutJob?.cancel()
            pendingRequestId = null
            state = if (result.successful) UnlockState.Success(result.message) else UnlockState.Failure(result.message)
            vibrate(result.successful)
        }
    }

    private fun requestUnlock() {
        if (state is UnlockState.Sending) return
        state = UnlockState.Sending
        val requestId = WireProtocol.newRequestId()
        pendingRequestId = requestId
        lifecycleScope.launch {
            val node = runCatching {
                Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                    .sortedByDescending { it.isNearby }
                    .firstOrNull()
            }.getOrNull()
            if (node == null) {
                pendingRequestId = null
                state = UnlockState.Failure("Phone not connected")
                vibrate(false)
                return@launch
            }
            val sent = runCatching {
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, WireProtocol.UNLOCK_REQUEST_PATH, WireProtocol.encodeRequest(requestId))
                    .await()
            }.isSuccess
            if (!sent) {
                pendingRequestId = null
                state = UnlockState.Failure("Could not reach phone")
                vibrate(false)
                return@launch
            }
            timeoutJob?.cancel()
            timeoutJob = launch {
                delay(15_000)
                if (pendingRequestId == requestId) {
                    pendingRequestId = null
                    state = UnlockState.Failure("SmartPlus timed out")
                    vibrate(false)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_UNLOCK, false) != true) return
        intent.removeExtra(EXTRA_REQUEST_UNLOCK)
        requestUnlock()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(success: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = if (success) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_DOUBLE_CLICK
            vibrator.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(if (success) 80 else 180, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private sealed interface UnlockState {
        val message: String

        data object Idle : UnlockState { override val message = "Ready" }
        data object Sending : UnlockState { override val message = "Asking phone" }
        data class Success(override val message: String) : UnlockState
        data class Failure(override val message: String) : UnlockState
    }

    companion object {
        const val EXTRA_REQUEST_UNLOCK = "com.ivanmalison.akuvoxwear.REQUEST_UNLOCK"
    }
}
