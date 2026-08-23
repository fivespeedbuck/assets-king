package com.assetsking.app.ui.privacy

import android.media.MediaPlayer
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assetsking.app.R
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * 全局隐私外壳：一次开关覆盖首页、下钻页和其余主导航页。
 * 真实金额与图形先由各页面替换；这里提供不读取业务数据的连续假值与一次性灰雾切换。
 */
@Composable
fun PrivacyModeSurface(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    PrivacyAudioLoop(enabled)
    val motionEnabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    }
    var chaosSeed by remember { mutableStateOf(Random.nextInt()) }
    var chaosTick by remember { mutableStateOf(0L) }
    val chaosFrame = remember(chaosSeed, chaosTick) { privacyChaosFrame(chaosSeed, chaosTick) }
    LaunchedEffect(enabled, motionEnabled) {
        if (!enabled) return@LaunchedEffect
        chaosSeed = Random.nextInt()
        chaosTick = 0L
        if (!motionEnabled) return@LaunchedEffect
        while (isActive) {
            delay(PRIVACY_CHAOS_TICK_MS)
            chaosTick += 1L
        }
    }

    CompositionLocalProvider(
        LocalPrivacyEnabled provides enabled,
        LocalPrivacyChaosFrame provides chaosFrame,
        LocalPrivacyMotionEnabled provides motionEnabled
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (enabled) Modifier.globalGrayscale() else Modifier)
                // 背景必须画进离屏去色层；放在其外侧会让透明像素被 Saturation 混合成灰白。
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (enabled) PrivacyChaosOverlay(chaosFrame)
            if (enabled) PrivacyAmbientFog(motionEnabled)
            if (enabled) PrivacyEmblemWatermark(Modifier.align(Alignment.BottomCenter))
            content()
            PrivacyFogTransition(trigger = enabled, motionEnabled = motionEnabled)
        }
    }
}

internal data class PrivacyAmbientFogSpec(
    val durationMillis: Int,
    val alpha: Float
)

internal val PrivacyAmbientFogSpecs = listOf(
    PrivacyAmbientFogSpec(durationMillis = 13_700, alpha = 0.21f),
    PrivacyAmbientFogSpec(durationMillis = 18_900, alpha = 0.16f),
    PrivacyAmbientFogSpec(durationMillis = 24_600, alpha = 0.12f),
    PrivacyAmbientFogSpec(durationMillis = 21_300, alpha = 0.10f)
)

internal data class PrivacyAmbientFogVariation(
    val offsetXDp: Float,
    val offsetYDp: Float,
    val scaleMultiplier: Float,
    val rotationOffset: Float,
    val direction: Float,
    val alphaMultiplier: Float,
    val flipX: Boolean,
    val flipY: Boolean
)

internal fun privacyAmbientFogVariations(seed: Int): List<PrivacyAmbientFogVariation> {
    val random = Random(seed)
    // 四层共同组成一个雾场：本次进入的场景种子决定整片雾场一起镜像。
    val entryFlipX = random.nextBoolean()
    val entryFlipY = random.nextBoolean()
    return PrivacyAmbientFogSpecs.map {
        PrivacyAmbientFogVariation(
            offsetXDp = random.nextFloat() * 36f - 18f,
            offsetYDp = random.nextFloat() * 32f - 16f,
            scaleMultiplier = 0.97f + random.nextFloat() * 0.07f,
            rotationOffset = random.nextFloat() * 4f - 2f,
            direction = if (random.nextBoolean()) 1f else -1f,
            alphaMultiplier = 0.90f + random.nextFloat() * 0.15f,
            flipX = entryFlipX,
            flipY = entryFlipY
        )
    }
}

/**
 * 隐秘稳定态的环境雾：只画在数据内容层后方，三层异步缓慢漂移。
 * 与一次性转场雾分离；系统关闭动画时保留静态淡雾，不强迫持续运动。
 */
@Composable
private fun PrivacyAmbientFog(motionEnabled: Boolean) {
    // 此 composable 只在隐秘模式内存在，因此每次重新进入都会得到一组新的稳定随机参数。
    val variations = remember { privacyAmbientFogVariations(Random.nextInt()) }
    val phases = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "privacy-ambient-fog")
        listOf(
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(PrivacyAmbientFogSpecs[0].durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "privacy-ambient-fog-back"
            ).value,
            transition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(PrivacyAmbientFogSpecs[1].durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "privacy-ambient-fog-middle"
            ).value,
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.95f,
                animationSpec = infiniteRepeatable(
                    animation = tween(PrivacyAmbientFogSpecs[2].durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "privacy-ambient-fog-drift"
            ).value,
            transition.animateFloat(
                initialValue = 0.72f,
                targetValue = 0.10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(PrivacyAmbientFogSpecs[3].durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "privacy-ambient-fog-upper-right"
            ).value
        )
    } else {
        listOf(0.52f, 0.48f, 0.58f, 0.42f)
    }
    val tint = PrivacyAmbientFogTint

    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        Image(
            painter = painterResource(R.drawable.privacy_fog_back),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val variation = variations[0]
                val phase = if (variation.direction > 0f) phases[0] else 1f - phases[0]
                val mirrorX = if (variation.flipX) -1f else 1f
                val mirrorY = if (variation.flipY) -1f else 1f
                // 透明度保持稳定，避免低透明度长周期渐变产生肉眼可见的色阶跳变；
                // 若隐若现由雾纹理自身的漂移、升沉和缩放完成。
                alpha = PrivacyAmbientFogSpecs[0].alpha * variation.alphaMultiplier
                translationX = ((-8f + 82f * phase) * mirrorX + variation.offsetXDp) * density
                translationY = ((-96f + 52f * phase) * mirrorY + variation.offsetYDp) * density
                rotationZ = 178.5f + 3f * phase + variation.rotationOffset
                scaleX = (1.68f + 0.08f * phase) * variation.scaleMultiplier * mirrorX
                scaleY = (1.42f + 0.08f * phase) * variation.scaleMultiplier * mirrorY
            }
        )
        Image(
            painter = painterResource(R.drawable.privacy_fog_middle),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val variation = variations[1]
                val phase = if (variation.direction > 0f) phases[1] else 1f - phases[1]
                val mirrorX = if (variation.flipX) -1f else 1f
                val mirrorY = if (variation.flipY) -1f else 1f
                alpha = PrivacyAmbientFogSpecs[1].alpha * variation.alphaMultiplier
                translationX = ((72f - 52f * phase) * mirrorX + variation.offsetXDp) * density
                translationY = ((-42f - 50f * phase) * mirrorY + variation.offsetYDp) * density
                rotationZ = 1.2f - 2.4f * phase + variation.rotationOffset
                scaleX = (1.72f + 0.09f * phase) * variation.scaleMultiplier * mirrorX
                scaleY = (1.34f + 0.10f * phase) * variation.scaleMultiplier * mirrorY
            }
        )
        Image(
            painter = painterResource(R.drawable.privacy_fog_drift),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val variation = variations[2]
                val phase = if (variation.direction > 0f) phases[2] else 1f - phases[2]
                val mirrorX = if (variation.flipX) -1f else 1f
                val mirrorY = if (variation.flipY) -1f else 1f
                alpha = PrivacyAmbientFogSpecs[2].alpha * variation.alphaMultiplier
                translationX = ((-24f + 68f * phase) * mirrorX + variation.offsetXDp) * density
                translationY = ((46f - 72f * phase) * mirrorY + variation.offsetYDp) * density
                rotationZ = -2.5f + 5f * phase + variation.rotationOffset
                scaleX = (1.82f + 0.08f * phase) * variation.scaleMultiplier * mirrorX
                scaleY = (1.38f + 0.12f * phase) * variation.scaleMultiplier * mirrorY
            }
        )
        Image(
            painter = painterResource(R.drawable.privacy_fog_fourth),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val variation = variations[3]
                val phase = if (variation.direction > 0f) phases[3] else 1f - phases[3]
                val mirrorX = if (variation.flipX) -1f else 1f
                val mirrorY = if (variation.flipY) -1f else 1f
                alpha = PrivacyAmbientFogSpecs[3].alpha * variation.alphaMultiplier
                // 普通第四层，与其余三层共享本次进入的整体镜像种子。
                translationX = ((34f + 54f * phase) * mirrorX + variation.offsetXDp) * density
                translationY = ((-82f - 54f * phase) * mirrorY + variation.offsetYDp) * density
                rotationZ = -3.8f + 3.2f * phase + variation.rotationOffset
                scaleX = (1.10f + 0.06f * phase) * variation.scaleMultiplier * mirrorX
                scaleY = (1.08f + 0.05f * phase) * variation.scaleMultiplier * mirrorY
            }
        )
    }
}

@Composable
private fun PrivacyEmblemWatermark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.90f)
            .aspectRatio(1f)
            .padding(bottom = 96.dp)
            // 只平移、不增加内边距，保持暗版与亮版徽记尺寸完全一致。
            .offset(y = (-80).dp)
            .clearAndSetSemantics { }
    ) {
        Image(
            painter = painterResource(R.drawable.ic_privacy_emblem_fog),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.background, BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = painterResource(R.drawable.ic_privacy_emblem_fog),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color(0xFFF0F1F3), BlendMode.SrcIn),
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.40f }
        )
    }
}

@Composable
private fun PrivacyAudioLoop(enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(enabled, context, lifecycleOwner) {
        fun createPlayer(): MediaPlayer? = if (enabled) {
            MediaPlayer.create(context, R.raw.privacy_chaos_loop_seamless)
        } else null

        var currentPlayer = createPlayer()
        var nextPlayer = createPlayer()
        lateinit var completionListener: MediaPlayer.OnCompletionListener
        completionListener = MediaPlayer.OnCompletionListener { completed ->
            if (completed !== currentPlayer) return@OnCompletionListener
            val finished = currentPlayer
            currentPlayer = nextPlayer
            nextPlayer = createPlayer()
            currentPlayer?.setOnCompletionListener(completionListener)
            runCatching { currentPlayer?.setNextMediaPlayer(nextPlayer) }
            runCatching { finished?.setOnCompletionListener(null) }
            runCatching { finished?.release() }
        }
        currentPlayer?.setOnCompletionListener(completionListener)
        runCatching { currentPlayer?.setNextMediaPlayer(nextPlayer) }

        fun startIfVisible() {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                runCatching { if (currentPlayer?.isPlaying == false) currentPlayer?.start() }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startIfVisible()
                Lifecycle.Event.ON_STOP -> runCatching { currentPlayer?.pause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        startIfVisible()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            listOfNotNull(currentPlayer, nextPlayer).distinct().forEach { player ->
                runCatching { player.setOnCompletionListener(null) }
                runCatching { player.setNextMediaPlayer(null) }
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            currentPlayer = null
            nextPlayer = null
        }
    }
}

// 所有假值共享同一个节拍；图形在这一拍内连续过渡，文字/金额按帧替换。
internal const val PRIVACY_CHAOS_FRAME_MS = 1_200L
internal const val PRIVACY_CHAOS_TICK_MS = PRIVACY_CHAOS_FRAME_MS

internal data class PrivacyChaosFrame(
    val seed: Int,
    val tick: Long,
    val fakeAmounts: List<String>,
    val innerRingFractions: List<Float>,
    val outerRingFractions: List<Float>,
    val progressFractions: List<Float>,
    val trendYFractions: List<Float>,
    val barFractions: List<Triple<Float, Float, Float>>
)

internal val LocalPrivacyChaosFrame = staticCompositionLocalOf { privacyChaosFrame(0) }
internal val LocalPrivacyMotionEnabled = staticCompositionLocalOf { true }

@Composable
internal fun animatePrivacyValue(target: Float, label: String): Float {
    val motionEnabled = LocalPrivacyMotionEnabled.current
    return animateFloatAsState(
        targetValue = target,
        animationSpec = if (LocalPrivacyEnabled.current && motionEnabled) {
            tween(PRIVACY_CHAOS_FRAME_MS.toInt(), easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = label
    ).value
}

@Composable
internal fun privacyFakeAmount(index: Int): String {
    val amounts = LocalPrivacyChaosFrame.current.fakeAmounts
    return amounts[Math.floorMod(index, amounts.size)]
}

@Composable
internal fun privacyFakeCompactAmount(index: Int): String =
    compactPrivacyAmount(privacyFakeAmount(index))

internal fun compactPrivacyAmount(amount: String): String {
    val noSeparator = amount.replace(",", "")
    return if (noSeparator.length >= 9) noSeparator.removeRange(2, 3) else noSeparator
}

@Composable
internal fun privacyFakeCount(index: Int): Int =
    LocalPrivacyChaosFrame.current.fakeInt(index, 1, 99)

@Composable
internal fun privacyFakePercent(index: Int): Int =
    LocalPrivacyChaosFrame.current.fakeInt(index, 3, 97)

@Composable
internal fun privacyFakeYearMonth(index: Int): String {
    val frame = LocalPrivacyChaosFrame.current
    return "${frame.fakeInt(index, 2024, 2035)}年${frame.fakeInt(index + 1, 1, 12)}月"
}

@Composable
internal fun privacyFakeDateTime(index: Int, includeDate: Boolean = true): String {
    val frame = LocalPrivacyChaosFrame.current
    val time = "%02d:%02d".format(
        frame.fakeInt(index + 3, 0, 23),
        frame.fakeInt(index + 4, 0, 59)
    )
    return if (includeDate) {
        "%02d-%02d %s".format(
            frame.fakeInt(index + 1, 1, 12),
            frame.fakeInt(index + 2, 1, 28),
            time
        )
    } else {
        time
    }
}

@Composable
internal fun privacyScrambleText(text: String, index: Int): String =
    LocalPrivacyChaosFrame.current.scramble(text, index)

@Composable
internal fun privacyMosaicText(index: Int, length: Int = 6): String =
    LocalPrivacyChaosFrame.current.mosaic(index, length)

@Composable
internal fun privacyObfuscatedText(text: String, index: Int): String =
    LocalPrivacyChaosFrame.current.obfuscate(text, index)

@Composable
internal fun privacyFakeIndex(index: Int, bound: Int): Int =
    if (bound <= 0) 0 else LocalPrivacyChaosFrame.current.fakeInt(index, 0, bound - 1)

private fun PrivacyChaosFrame.random(index: Int): Random =
    Random(seed xor (index * -1640531527) xor tick.hashCode())

private fun PrivacyChaosFrame.fakeInt(index: Int, start: Int, endInclusive: Int): Int =
    random(index).nextInt(start, endInclusive + 1)

private fun PrivacyChaosFrame.scramble(text: String, index: Int): String {
    if (text.isBlank()) return mosaic(index)
    if (text.length == 1) return mosaic(index, 1)
    val original = text.toList()
    val shuffled = original.shuffled(random(index)).toMutableList()
    if (shuffled == original) {
        val first = shuffled.removeAt(0)
        shuffled.add(first)
    }
    return shuffled.joinToString("")
}

private fun PrivacyChaosFrame.mosaic(index: Int, length: Int = 6): String {
    val glyphs = "@#%&?+ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    val random = random(index)
    return buildString(length.coerceIn(1, 10)) {
        repeat(length.coerceIn(1, 10)) { append(glyphs[random.nextInt(glyphs.length)]) }
    }
}

private fun PrivacyChaosFrame.obfuscate(text: String, index: Int): String {
    val random = random(index)
    val noise = "@#%&?+ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    val pool = (text.repeat(3) + noise).toList()
    return buildString((text.length + 2).coerceIn(3, 10)) {
        repeat((text.length + 2).coerceIn(3, 10)) { append(pool[random.nextInt(pool.size)]) }
    }
}

internal fun privacyChaosFrame(seed: Int, tick: Long = 0L): PrivacyChaosFrame {
    fun random(index: Int) = Random(seed xor (index * -1640531527) xor tick.hashCode())
    fun normalized(count: Int): List<Float> {
        val values = List(count) { index -> random(index).nextFloat(0.2f..1f) }
        val total = values.sum()
        return values.map { it / total }
    }
    return PrivacyChaosFrame(
        seed = seed,
        tick = tick,
        fakeAmounts = List(8) { index -> random(index + 20).fakeMoneyFragment() },
        innerRingFractions = normalized(4),
        outerRingFractions = normalized(6).reversed(),
        progressFractions = List(16) { index -> random(index + 40).nextFloat(0.08f..0.96f) },
        trendYFractions = List(9) { index -> random(index + 60).nextFloat(0.22f..0.78f) },
        barFractions = List(12) { index ->
            val random = random(index + 80)
            Triple(
                random.nextFloat(0.22f..0.98f),
                random.nextFloat(0.12f..0.88f),
                random.nextFloat(0.08f..0.72f)
            )
        }
    )
}

internal data class PrivacyChaosPoint(val x: Float, val y: Float)

internal data class PrivacyChaosRing(
    val center: PrivacyChaosPoint,
    val radius: Float,
    val startAngle: Float,
    val sweepAngle: Float
)

internal data class PrivacyChaosLabel(
    val position: PrivacyChaosPoint,
    val text: String
)

internal data class PrivacyChaosPattern(
    val fog: List<Pair<PrivacyChaosPoint, Float>>,
    val rings: List<PrivacyChaosRing>,
    val trend: List<PrivacyChaosPoint>,
    val labels: List<PrivacyChaosLabel>
)

/** 只依赖随机种子，函数签名刻意不接收任何真实账务数据。 */
internal fun privacyChaosPattern(seed: Int): PrivacyChaosPattern {
    val random = Random(seed)
    fun point(xRange: ClosedFloatingPointRange<Float>, yRange: ClosedFloatingPointRange<Float>) =
        PrivacyChaosPoint(random.nextFloat(xRange), random.nextFloat(yRange))

    val fog = List(5) {
        point(-0.05f..1.05f, 0.02f..0.98f) to random.nextFloat(0.16f..0.32f)
    }
    val rings = List(3) {
        PrivacyChaosRing(
            center = point(0.14f..0.86f, 0.16f..0.84f),
            radius = random.nextFloat(0.08f..0.16f),
            startAngle = random.nextFloat(0f..360f),
            sweepAngle = random.nextFloat(110f..280f)
        )
    }
    val trend = List(9) { index ->
        PrivacyChaosPoint(
            x = 0.05f + index * (0.9f / 8f),
            y = random.nextFloat(0.28f..0.72f)
        )
    }
    val labels = List(8) {
        PrivacyChaosLabel(
            position = point(0.04f..0.74f, 0.08f..0.9f),
            text = random.fakeMoneyFragment()
        )
    }
    return PrivacyChaosPattern(fog, rings, trend, labels)
}

@Composable
private fun PrivacyChaosOverlay(frame: PrivacyChaosFrame) {
    val pattern = remember { privacyChaosPattern(Random.nextInt()) }
    val traceColor = Color.White
    val textMeasurer = rememberTextMeasurer()
    val firstTrend = frame.trendYFractions.mapIndexed { index, value ->
        animatePrivacyValue(value, "privacy-background-trend-a-$index")
    }
    // 三条都保持最早版的温和范围，但错位取样，避免看起来像三条平行线。
    val secondTrend = firstTrend.mapIndexed { index, value ->
        (value * 0.35f + firstTrend[(index + 3) % firstTrend.size] * 0.65f + 0.14f)
            .coerceIn(0.10f, 0.90f)
    }
    val thirdTrend = firstTrend.mapIndexed { index, value ->
        (value * 0.30f + firstTrend[(firstTrend.lastIndex - index + 2) % firstTrend.size] * 0.70f - 0.14f)
            .coerceIn(0.10f, 0.90f)
    }
    val trendBaseAlphas = listOf(0.14f, 0.11f, 0.09f)
    val trendBaseWidths = listOf(1.9f, 1.55f, 1.3f)
    val trendAlphas = List(3) { index ->
        animatePrivacyValue(
            trendBaseAlphas[index] * (0.68f + frame.progressFractions[10 + index] * 0.48f),
            "privacy-background-trend-alpha-$index"
        )
    }
    val trendWidths = List(3) { index ->
        animatePrivacyValue(
            trendBaseWidths[index] * (0.76f + frame.progressFractions[13 + index] * 0.42f),
            "privacy-background-trend-width-$index"
        )
    }
    val floatingNumbers = List(8) { index ->
        val anchor = pattern.labels[index].position
        val x = animatePrivacyValue(
            (anchor.x + (frame.progressFractions[index] - 0.5f) * 0.06f).coerceIn(0.03f, 0.82f),
            "privacy-background-number-x-$index"
        )
        val y = animatePrivacyValue(
            (anchor.y + (frame.progressFractions[index + 4] - 0.5f) * 0.04f).coerceIn(0.06f, 0.92f),
            "privacy-background-number-y-$index"
        )
        val numberAlpha = animatePrivacyValue(
            0.10f + frame.progressFractions[index + 8] * 0.12f,
            "privacy-background-number-alpha-$index"
        )
        Triple(x, y, numberAlpha)
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
    ) {
        val alpha = 1f
        val trendHeight = size.height

        val trendPath = Path().apply {
            pattern.trend.forEachIndexed { index, point ->
                val x = point.x * size.width
                val y = firstTrend[index % firstTrend.size] * trendHeight
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = trendPath,
            color = traceColor.copy(alpha = trendAlphas[0] * alpha),
            style = Stroke(
                width = trendWidths[0] * density,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(13f, 9f))
            )
        )

        val secondTrendPath = Path().apply {
            pattern.trend.forEachIndexed { index, point ->
                val x = point.x * size.width
                val y = secondTrend[index % secondTrend.size] * trendHeight
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = secondTrendPath,
            color = traceColor.copy(alpha = trendAlphas[1] * alpha),
            style = Stroke(
                width = trendWidths[1] * density,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 12f), phase = 5f)
            )
        )

        val thirdTrendPath = Path().apply {
            pattern.trend.forEachIndexed { index, point ->
                val x = point.x * size.width
                val y = thirdTrend[index % thirdTrend.size] * trendHeight
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = thirdTrendPath,
            color = traceColor.copy(alpha = trendAlphas[2] * alpha),
            style = Stroke(
                width = trendWidths[2] * density,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 15f), phase = 9f)
            )
        )

        floatingNumbers.forEachIndexed { index, (xFraction, yFraction, numberAlpha) ->
            val text = frame.fakeAmounts[index]
            val style = TextStyle(
                color = traceColor.copy(alpha = numberAlpha),
                fontSize = (11 + index % 3).sp,
                fontWeight = FontWeight.Medium
            )
            val layout = textMeasurer.measure(text, style = style)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    (xFraction * size.width).coerceAtMost(size.width - layout.size.width),
                    (yFraction * size.height).coerceAtMost(size.height - layout.size.height)
                )
            )
        }

    }
}

@Composable
private fun PrivacyFogTransition(trigger: Boolean, motionEnabled: Boolean) {
    val fogAlpha = remember { Animatable(0f) }
    val fogTravel = remember { Animatable(0f) }
    var previousTrigger by remember { mutableStateOf<Boolean?>(null) }
    // 切换后的第一帧就覆盖旧主题底色，避免先闪出新主题再补遮罩。
    val themeCoverAlpha = remember(trigger) {
        Animatable(if (previousTrigger == null) 0f else 1f)
    }

    LaunchedEffect(trigger, motionEnabled) {
        val firstComposition = previousTrigger == null
        previousTrigger = trigger
        if (firstComposition && !trigger) {
            return@LaunchedEffect
        }
        if (!motionEnabled) {
            fogAlpha.snapTo(0f)
            fogTravel.snapTo(0f)
            themeCoverAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        fogAlpha.snapTo(0f)
        fogTravel.snapTo(0f)
        coroutineScope {
            launch {
                fogTravel.animateTo(1f, tween(durationMillis = 2_800, easing = LinearEasing))
            }
            launch {
                fogAlpha.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
                delay(1_250L)
                fogAlpha.animateTo(0f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
            }
            launch {
                themeCoverAlpha.animateTo(0f, tween(durationMillis = 2_200, easing = FastOutSlowInEasing))
            }
        }
    }

    if (fogAlpha.value > 0f || themeCoverAlpha.value > 0f) {
        val fogTint = if (trigger) PrivacyFogEnterTint else PrivacyFogExitTint
        val ambientFog = if (trigger) Color(0xFFB8BBC1) else Color(0xFF62666D)
        val previousThemeColor = if (trigger) Color.White else Color.Black
        Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(previousThemeColor.copy(alpha = themeCoverAlpha.value))
                val ambientArrival = if (trigger) (fogTravel.value * 1.8f).coerceIn(0f, 1f) else 1f
                drawRect(ambientFog.copy(alpha = 0.24f * fogAlpha.value * ambientArrival))
            }
            Image(
                painter = painterResource(R.drawable.privacy_fog_front),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.38f * fogAlpha.value
                        translationX = if (trigger) {
                            (-92f + 76f * fogTravel.value) * density
                        } else {
                            -88f * fogTravel.value * density
                        }
                        translationY = sin(fogTravel.value * Math.PI).toFloat() * 18f * density
                        scaleX = 1.70f + 0.08f * fogTravel.value
                        scaleY = 1.18f + 0.06f * fogTravel.value
                    }
            )
            Image(
                painter = painterResource(R.drawable.privacy_fog_middle),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.28f * fogAlpha.value
                        translationX = if (trigger) {
                            (92f - 76f * fogTravel.value) * density
                        } else {
                            88f * fogTravel.value * density
                        }
                        translationY = -sin(fogTravel.value * Math.PI).toFloat() * 12f * density
                        scaleX = -(1.76f + 0.06f * fogTravel.value)
                        scaleY = 1.26f
                    }
            )
            Image(
                painter = painterResource(R.drawable.privacy_fog_back),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.20f * fogAlpha.value
                        translationX = if (trigger) {
                            (-78f + 65f * fogTravel.value) * density
                        } else {
                            -82f * fogTravel.value * density
                        }
                        translationY = (42f - 84f * fogTravel.value) * density
                        rotationZ = 180f
                        scaleX = 1.82f
                        scaleY = 1.38f
                    }
            )
            Image(
                painter = painterResource(R.drawable.privacy_fog_drift),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.16f * fogAlpha.value
                        translationX = if (trigger) {
                            (82f - 68f * fogTravel.value) * density
                        } else {
                            86f * fogTravel.value * density
                        }
                        translationY = sin((fogTravel.value + 0.55f) * Math.PI).toFloat() * 26f * density
                        rotationZ = -2f + 4f * fogTravel.value
                        scaleX = 1.88f
                        scaleY = 1.42f
                    }
            )
            Image(
                painter = painterResource(R.drawable.privacy_fog_full_veil),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val centerArrival = if (trigger) (fogTravel.value * 1.7f).coerceIn(0f, 1f) else 1f
                        alpha = 0.48f * fogAlpha.value * centerArrival
                        translationX = if (trigger) 0f else 26f * sin(fogTravel.value * Math.PI).toFloat() * density
                        translationY = (26f - 52f * fogTravel.value) * density
                        rotationZ = -0.8f + 1.6f * fogTravel.value
                        scaleX = if (trigger) 1.52f + 0.18f * fogTravel.value else 1.46f + 0.30f * fogTravel.value
                        scaleY = if (trigger) 1.24f + 0.30f * fogTravel.value else 0.98f + 0.62f * fogTravel.value
                    }
            )
            Image(
                painter = painterResource(R.drawable.privacy_fog_dense_bank),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.40f * fogAlpha.value
                        translationX = if (trigger) {
                            (-96f + 78f * fogTravel.value) * density
                        } else {
                            -92f * fogTravel.value * density
                        }
                        translationY = sin((fogTravel.value + 0.25f) * Math.PI).toFloat() * 30f * density
                        rotationZ = 1.5f - 3f * fogTravel.value
                        scaleX = -1.82f
                        scaleY = 1.55f
                    }
            )
            Image(
                painter = painterResource(R.drawable.privacy_fog_low_bank),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(fogTint, BlendMode.SrcIn),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.34f * fogAlpha.value
                        translationX = if (trigger) {
                            (96f - 78f * fogTravel.value) * density
                        } else {
                            92f * fogTravel.value * density
                        }
                        translationY = (72f - 144f * fogTravel.value) * density
                        rotationZ = -1f + 2f * fogTravel.value
                        scaleX = 1.86f
                        scaleY = 1.46f
                    }
            )
        }
    }
}

private val PrivacyFogEnterTint = Color(0xFFD7D9DD)
private val PrivacyFogExitTint = Color(0xFF70747B)
private val PrivacyAmbientFogTint = Color(0xFFC7CBD0)

private fun Modifier.globalGrayscale(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        graphicsLayer {
            renderEffect = android.graphics.RenderEffect.createColorFilterEffect(
                android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) }
                )
            ).asComposeRenderEffect()
        }
    } else {
        this
    }

private fun Random.nextFloat(range: ClosedFloatingPointRange<Float>): Float =
    range.start + nextFloat() * (range.endInclusive - range.start)

private fun Random.fakeMoneyFragment(): String {
    val sign = if (nextBoolean()) "+" else "−"
    // 隐私金额保持等长，换帧时只换字符，不推动周围布局。
    val whole = nextInt(1_000, 10_000).toString().reversed().chunked(3).joinToString(",").reversed()
    val cents = nextInt(0, 100).toString().padStart(2, '0')
    return "$sign¥$whole.$cents"
}
