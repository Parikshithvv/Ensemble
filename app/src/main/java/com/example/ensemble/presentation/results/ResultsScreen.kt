package com.example.ensemble.presentation.results

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ensemble.domain.Person
import com.example.ensemble.ui.theme.*
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    state: ResultsState,
    onAction: (ResultsAction) -> Unit,
    onNavigateBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepMochaBackground, DeepMochaGradientEnd)
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        text = "Clustering Results",
                        fontWeight = FontWeight.Bold,
                        color = WarmPeachText
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = WarmPeachText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WarmSkinSurface
                )
            )

            // Content List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Overview Header Card
                item {
                    OverviewCard(
                        totalPeople = state.persons.size,
                        totalFaces = state.totalFacesDetected,
                        totalFrames = state.totalFramesProcessed
                    )
                }

                // People List
                itemsIndexed(state.persons) { index, person ->
                    PersonResultCard(person = person, rank = index + 1)
                }
            }

            // Bottom Action Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WarmSkinSurface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Preview Collage Button
                    OutlinedButton(
                        onClick = { onAction(ResultsAction.GenerateCollage) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(
                                listOf(WarmOrangePrimary, SoftPeachAccent)
                            )
                        )
                    ) {
                        if (state.isGeneratingCollage) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = WarmPeachText,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Preview Collage",
                                color = WarmPeachText,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Share Button
                    Button(
                        onClick = { onAction(ResultsAction.ShareCollage) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarmOrangePrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Share",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // Collage Preview Dialog
        if (state.showCollagePreviewDialog && state.collageUri != null) {
            CollagePreviewDialog(
                uri = state.collageUri,
                onDismiss = { onAction(ResultsAction.DismissCollagePreview) },
                onShare = { onAction(ResultsAction.ShareCollage) }
            )
        }
    }
}

@Composable
private fun OverviewCard(
    totalPeople: Int,
    totalFaces: Int,
    totalFrames: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = WarmSkinSurface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Summary Metrics",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WarmPeachText
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricTile(title = "People Found", value = "$totalPeople")
                MetricTile(title = "Total Faces", value = "$totalFaces")
                MetricTile(title = "Frames Analysed", value = "$totalFrames")
            }
        }
    }
}

@Composable
private fun MetricTile(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = SoftPeachAccent
        )
        Text(
            text = title,
            fontSize = 12.sp,
            color = WarmSubtext
        )
    }
}

@Composable
private fun PersonResultCard(person: Person, rank: Int) {
    val repFace = person.representativeFace
    val croppedAvatar: Bitmap? = remember(person) {
        repFace?.let { cropFaceBitmap(it.fullFrame, it.boundingBox) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WarmSkinSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular Face Crop Avatar (shows face + shoulders/context)
            if (croppedAvatar != null) {
                Image(
                    bitmap = croppedAvatar.asImageBitmap(),
                    contentDescription = person.id,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(2.dp, WarmOrangePrimary, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(WarmSkinCardBorder),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#$rank",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = person.id,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    repFace?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "%.2f".format(it.qualityScore),
                                fontSize = 12.sp,
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "${person.faces.size} face shots • ${person.appearanceCount} appearance segment(s)",
                    fontSize = 13.sp,
                    color = SoftPeachAccent,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(8.dp))

                // Appearance timestamp chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    person.appearanceSegments.take(3).forEach { seg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WarmSkinCardBorder
                        ) {
                            Text(
                                text = "${formatMs(seg.startMs)} – ${formatMs(seg.endMs)}",
                                fontSize = 11.sp,
                                color = WarmPeachText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (person.appearanceSegments.size > 3) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WarmSkinCardBorder
                        ) {
                            Text(
                                text = "+${person.appearanceSegments.size - 3} more",
                                fontSize = 11.sp,
                                color = WarmSubtext,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollagePreviewDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val collageBitmap: Bitmap? = remember(uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = WarmSkinSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Collage Preview",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarmPeachText
                    )

                    TextButton(onClick = onDismiss) {
                        Text("Close", color = WarmSubtext)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DeepMochaBackground),
                    contentAlignment = Alignment.Center
                ) {
                    if (collageBitmap != null) {
                        Image(
                            bitmap = collageBitmap.asImageBitmap(),
                            contentDescription = "Generated Collage",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = WarmOrangePrimary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WarmOrangePrimary)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share Collage Image", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Generous face crop: expands bounding box by 70% on each side to show face + shoulders/context.
 */
private fun cropFaceBitmap(fullFrame: Bitmap, boundingBox: RectF): Bitmap {
    val marginX = boundingBox.width() * 0.70f
    val marginY = boundingBox.height() * 0.70f

    val left = max(0f, boundingBox.left - marginX).toInt()
    val top = max(0f, boundingBox.top - marginY).toInt()
    val right = min(fullFrame.width.toFloat(), boundingBox.right + marginX).toInt()
    val bottom = min(fullFrame.height.toFloat(), boundingBox.bottom + marginY).toInt()

    val w = max(1, right - left)
    val h = max(1, bottom - top)

    return Bitmap.createBitmap(fullFrame, left, top, w, h)
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
