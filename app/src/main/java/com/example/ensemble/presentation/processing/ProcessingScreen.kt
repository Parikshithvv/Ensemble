package com.example.ensemble.presentation.processing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ensemble.ui.theme.*
import org.koin.androidx.compose.koinViewModel

// ── Root composable (holds ViewModel, handles events) ─────────────────────
@Composable
fun ProcessingRoot(
    onNavigateToResults: () -> Unit,
    viewModel: ProcessingViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onAction(ProcessingAction.VideoSelected(it)) }
    }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProcessingEvent.LaunchVideoPicker -> videoPicker.launch("video/*")
                is ProcessingEvent.NavigateToResults -> onNavigateToResults()
                is ProcessingEvent.ShowError -> { /* Snackbar */ }
            }
        }
    }

    ProcessingScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

// ── Screen composable (pure: state + onAction, previewable) ───────────────
@Composable
fun ProcessingScreen(
    state: ProcessingState,
    onAction: (ProcessingAction) -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(durationMillis = 300),
        label = "progress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepMochaBackground, DeepMochaGradientEnd)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            // Title
            Text(
                text = "Ensemble",
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                color = WarmPeachText,
                letterSpacing = (-1).sp
            )
            Text(
                text = "Identify every person.\nCapture their best moment.",
                fontSize = 15.sp,
                color = WarmSubtext,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(8.dp))

            if (!state.isProcessing && !state.isDone) {
                // Pick video button
                Button(
                    onClick = { onAction(ProcessingAction.PickVideo) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarmOrangePrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Pick a Video",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.selectedVideoUri != null) {
                    Text(
                        text = "Selected: ${state.selectedVideoUri.lastPathSegment ?: "video"}",
                        color = WarmSubtext,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (state.isProcessing || state.isDone) {
                // Progress card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = WarmSkinSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (state.isDone) "Processing Complete" else "Extracting Frames…",
                                color = WarmPeachText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${(animatedProgress * 100).toInt()}%",
                                color = SoftPeachAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = WarmOrangePrimary,
                            trackColor = WarmSkinCardBorder
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Frames: ${state.framesProcessed} / ${state.totalFrames}",
                                color = WarmSubtext,
                                fontSize = 13.sp
                            )
                            if (state.facesDetected > 0) {
                                Text(
                                    text = "Faces: ${state.facesDetected}",
                                    color = WarmSubtext,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        if (state.isDone) {
                            Button(
                                onClick = { onAction(ProcessingAction.NavigateToResults) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WarmGreenSuccess
                                )
                            ) {
                                Text("View Results", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Error message
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1010))
                ) {
                    Text(
                        text = "Error: ${error.javaClass.simpleName}",
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────
@Preview(showBackground = true, backgroundColor = 0xFF1C120C)
@Composable
private fun ProcessingScreenIdlePreview() {
    EnsembleTheme {
        ProcessingScreen(
            state = ProcessingState(),
            onAction = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C120C)
@Composable
private fun ProcessingScreenInProgressPreview() {
    EnsembleTheme {
        ProcessingScreen(
            state = ProcessingState(
                isProcessing = true,
                progress = 0.47f,
                framesProcessed = 47,
                totalFrames = 100
            ),
            onAction = {}
        )
    }
}
