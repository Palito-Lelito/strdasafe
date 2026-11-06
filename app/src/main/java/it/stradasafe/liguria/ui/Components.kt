package it.stradasafe.liguria.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalIsDark.current,
    elevation: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val bg = if (isDark) StradaColors.DarkSurface else StradaColors.LightSurface
    val border = if (isDark) StradaColors.DarkBorder else StradaColors.LightBorder
    Surface(
        modifier = modifier,
        shape = StradaShapes.Large,
        color = bg,
        border = BorderStroke(1.dp, border),
        shadowElevation = elevation,
        tonalElevation = 0.dp
    ) {
        Column(content = content)
    }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush = StradaGradients.Primary,
    icon: String? = null
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(StradaShapes.Medium)
            .background(if (enabled) gradient else Brush.linearGradient(
                listOf(Color.Gray.copy(alpha = 0.4f), Color.Gray.copy(alpha = 0.4f))
            ))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
fun Speedometer(
    speed: Int,
    speedLimit: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 110.dp
) {
    val isOver = speedLimit != null && speed > speedLimit
    val targetProgress = if (speedLimit != null) {
        (speed.toFloat() / (speedLimit * 1.5f)).coerceIn(0f, 1f)
    } else {
        (speed.toFloat() / 180f).coerceIn(0f, 1f)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "speed"
    )
    val color = when {
        isOver -> StradaColors.Red
        speedLimit == null -> StradaColors.Cyan
        speed > speedLimit * 0.9f -> StradaColors.Amber
        else -> StradaColors.Cyan
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val radius = (this.size.minDimension - stroke) / 2
            val cx = this.size.width / 2
            val cy = this.size.height / 2
            drawArc(
                color = color.copy(alpha = 0.15f),
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = 135f, sweepAngle = 270f * animatedProgress, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$speed", fontSize = (size.value * 0.28f).sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                if (speedLimit != null) "LIMITE $speedLimit" else "KM/H",
                fontSize = 10.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    large: Boolean = false
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = if (large) 30.sp else 22.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Text(
            label, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 6.dp)
    )
}

fun formatDistance(meters: Double): String = when {
    meters.isNaN() -> "..."
    meters < 10 -> "Ora"
    meters < 1000 -> "${meters.roundToInt()} m"
    else -> String.format("%.1f km", meters / 1000)
}

fun formatDuration(seconds: Double): String {
    val min = (seconds / 60).roundToInt()
    return when {
        min < 1 -> "< 1 min"
        min < 60 -> "$min min"
        else -> "${min / 60}h ${min % 60}m"
    }
}
