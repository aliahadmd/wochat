package com.aliahad.aichat.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aliahad.aichat.voice.VoiceCallPhase
import com.aliahad.aichat.voice.VoiceCallState

/**
 * The call surface: its own screen, not the chat with a banner.
 *
 * The animation is feedback rather than decoration — plan 036's point is that
 * silence with no visible state reads as a hang, and a voice turn can spend
 * seconds in THINKING. It therefore reflects the phase exactly, and honours
 * reduced-motion by falling back to a static indicator.
 */
@Composable
fun VoiceCallScreen(
    state: VoiceCallState,
    memoryEnabled: Boolean,
    onInterrupt: () -> Unit,
    onHangUp: () -> Unit,
    reduceMotion: Boolean = false,
) {
    var showTranscript by remember { mutableStateOf(true) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (memoryEnabled) "Memory on" else "Memory off",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showTranscript = !showTranscript }) {
                    Text(if (showTranscript) "Hide transcript" else "Show transcript")
                }
            }

            Spacer(Modifier.weight(1f))

            PhaseIndicator(phase = state.phase, reduceMotion = reduceMotion)
            Spacer(Modifier.height(20.dp))
            Text(
                text = state.phase.label(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (showTranscript) {
                Spacer(Modifier.height(24.dp))
                val transcript = when {
                    state.phase == VoiceCallPhase.SPEAKING && state.spoken.isNotBlank() -> state.spoken
                    state.heard.isNotBlank() -> state.heard
                    else -> ""
                }
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                TextButton(
                    onClick = onInterrupt,
                    enabled = state.phase == VoiceCallPhase.SPEAKING ||
                        state.phase == VoiceCallPhase.THINKING,
                ) {
                    Text("Interrupt")
                }
                Button(
                    onClick = onHangUp,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("End call")
                }
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: VoiceCallPhase, reduceMotion: Boolean) {
    val colour = when (phase) {
        VoiceCallPhase.LISTENING -> MaterialTheme.colorScheme.primary
        VoiceCallPhase.THINKING -> MaterialTheme.colorScheme.tertiary
        VoiceCallPhase.SPEAKING -> MaterialTheme.colorScheme.secondary
        VoiceCallPhase.IDLE -> MaterialTheme.colorScheme.outline
    }
    // Each phase gets a distinguishable rhythm, not just a colour: listening
    // breathes slowly, thinking pulses faster, speaking is steady.
    val period = when (phase) {
        VoiceCallPhase.LISTENING -> 1_600
        VoiceCallPhase.THINKING -> 700
        else -> 0
    }
    val scale = if (reduceMotion || period == 0) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "call-phase")
        val animated by transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = period, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "call-phase-scale",
        )
        animated
    }
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .background(color = colour.copy(alpha = 0.25f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color = colour, shape = CircleShape),
        )
    }
}

private fun VoiceCallPhase.label(): String = when (this) {
    VoiceCallPhase.IDLE -> "Starting"
    VoiceCallPhase.LISTENING -> "Listening"
    VoiceCallPhase.THINKING -> "Thinking"
    VoiceCallPhase.SPEAKING -> "Speaking"
}
