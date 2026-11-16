package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ai.VoiceSessionState
import com.example.core.AurixIdentity
import com.example.ui.theme.AurixElectricBlue
import com.example.ui.theme.AurixEmerald
import com.example.ui.theme.AurixMagentaGlow
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixNeonPurple
import com.example.ui.theme.AurixObsidian
import com.example.ui.theme.AurixRoseError
import com.example.ui.theme.AurixTextMuted
import com.example.ui.theme.AurixTextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

object AurixLogoCache {
    @Volatile
    private var cachedLogo: ImageBitmap? = null
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getCached(): ImageBitmap? = cachedLogo

    fun preloadAsync(context: Context) {
        if (cachedLogo != null) return
        val appContext = context.applicationContext
        preloadScope.launch {
            getOrDecode(appContext)
        }
    }

    suspend fun getOrDecode(context: Context): ImageBitmap? {
        cachedLogo?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedLogo ?: run {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 2
                }
                val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.aurix_logo, options)
                    ?: BitmapFactory.decodeResource(context.resources, R.drawable.aurix_logo)
                bmp?.asImageBitmap()?.also { cachedLogo = it }
            }
        }
    }
}

@Composable
fun AurixLogoMark(
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp
) {
    if (LocalInspectionMode.current) {
        Image(
            painter = painterResource(id = R.drawable.aurix_logo),
            contentDescription = stringResource(id = R.string.logo_content_description),
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
        )
        return
    }

    val context = LocalContext.current
    var bitmap by remember { mutableStateOf(AurixLogoCache.getCached()) }

    LaunchedEffect(Unit) {
        if (bitmap == null) {
            bitmap = AurixLogoCache.getOrDecode(context)
        }
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = stringResource(id = R.string.logo_content_description),
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(AurixObsidian)
        )
    }
}

@Composable
fun AurixBrandHeader(
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        AurixLogoMark(size = 44.dp, cornerRadius = 12.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AurixIdentity.APP_NAME,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AurixNeonPurple.copy(alpha = 0.22f))
                        .border(1.dp, AurixNeonPurple.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "by ${AurixIdentity.OWNER_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE9C8FF),
                        fontSize = 10.sp
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = AurixTextSecondary
            )
        }
    }
}

@Composable
fun AurixVoiceOrb(
    voiceState: VoiceSessionState,
    micAmplitude: Float,
    aiAmplitude: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 176.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "aurix_orb")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    val liveLevel = when (voiceState) {
        VoiceSessionState.SPEAKING -> aiAmplitude
        VoiceSessionState.LISTENING -> micAmplitude
        VoiceSessionState.CONNECTING -> 0.45f
        else -> 0.08f
    }

    val animatedLevel by animateFloatAsState(
        targetValue = liveLevel,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 360f),
        label = "live_level"
    )

    val primaryGlow = when (voiceState) {
        VoiceSessionState.SPEAKING -> AurixNeonPurple
        VoiceSessionState.LISTENING -> AurixNeonCyan
        VoiceSessionState.CONNECTING -> AurixElectricBlue
        VoiceSessionState.MUTED -> AurixTextMuted
        VoiceSessionState.ERROR -> AurixRoseError
        VoiceSessionState.DISCONNECTED -> AurixElectricBlue
    }
    val secondaryGlow = when (voiceState) {
        VoiceSessionState.SPEAKING -> AurixMagentaGlow
        VoiceSessionState.LISTENING -> AurixEmerald
        else -> AurixNeonPurple
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(orbSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("aurix_voice_orb")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.38f
            val reactiveRadius = baseRadius * (idlePulse + animatedLevel * 0.24f)

            // Outer ambient halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.28f + animatedLevel * 0.25f),
                        secondaryGlow.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.50f
                ),
                radius = size.minDimension * 0.50f,
                center = center
            )

            // Rotating neon dual-arc ring
            rotate(degrees = rotation, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            primaryGlow,
                            secondaryGlow,
                            Color.Transparent,
                            primaryGlow
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(center.x - reactiveRadius, center.y - reactiveRadius),
                    size = Size(reactiveRadius * 2f, reactiveRadius * 2f),
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Counter-rotating inner technical ring
            rotate(degrees = -rotation * 1.4f, pivot = center) {
                val innerR = reactiveRadius * 0.88f
                drawArc(
                    color = secondaryGlow.copy(alpha = 0.65f),
                    startAngle = 30f,
                    sweepAngle = 200f,
                    useCenter = false,
                    topLeft = Offset(center.x - innerR, center.y - innerR),
                    size = Size(innerR * 2f, innerR * 2f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Exact user-provided AURIX AI logo preserved inside the orb core
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(orbSize * 0.58f)
                .clip(RoundedCornerShape(22.dp))
                .background(AurixObsidian)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(listOf(primaryGlow, secondaryGlow)),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(4.dp)
        ) {
            AurixLogoMark(
                size = orbSize * 0.54f,
                cornerRadius = 18.dp
            )
        }
    }
}

@Composable
fun VoiceWaveformVisualizer(
    voiceState: VoiceSessionState,
    micAmplitude: Float,
    aiAmplitude: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 22
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val activeAmp = when (voiceState) {
        VoiceSessionState.SPEAKING -> aiAmplitude.coerceAtLeast(0.25f)
        VoiceSessionState.LISTENING -> micAmplitude.coerceAtLeast(0.14f)
        VoiceSessionState.CONNECTING -> 0.22f
        else -> 0.04f
    }

    val barBrush = when (voiceState) {
        VoiceSessionState.SPEAKING -> Brush.verticalGradient(listOf(AurixMagentaGlow, AurixNeonPurple))
        VoiceSessionState.LISTENING -> Brush.verticalGradient(listOf(AurixNeonCyan, AurixElectricBlue))
        else -> Brush.verticalGradient(listOf(AurixTextMuted.copy(alpha = 0.5f), AurixTextMuted.copy(alpha = 0.25f)))
    }

    Canvas(
        modifier = modifier
            .height(34.dp)
    ) {
        val spacing = 4.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(3.dp.toPx())
        val midY = size.height / 2f

        for (i in 0 until barCount) {
            val normPos = i.toFloat() / barCount.toFloat()
            val envelope = sin(normPos * Math.PI).toFloat().coerceAtLeast(0.25f)
            val wave = (sin(phase + i * 0.55f) * 0.5f + 0.5f)
            val heightRatio = (0.14f + activeAmp * envelope * (0.45f + wave * 0.55f)).coerceIn(0.12f, 1.0f)
            val barH = size.height * heightRatio
            val x = i * (barWidth + spacing)
            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
