package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.theme.AppTheme
import com.example.ui.theme.Dimens

/**
 * The first screen a learner meets: the product, and two ways in.
 *
 * It appears only when there is no session (see `MainActivity`), and it decides
 * nothing about authentication itself - [onGetStarted] opens the existing sign-up
 * form and [onLogIn] opens the existing sign-in form. There is no account created
 * here and no credential asked for here; this screen only chooses which of the two
 * existing forms to open.
 *
 * The illustration is drawn rather than shipped as a picture. A bitmap would have to
 * be a second colour system in the app: it would carry its own light-mode sky in a
 * screen that has to work in both themes, and it would be a large asset for a
 * decoration. Drawn from the theme's own colours, the same composition is a pale
 * blue dawn in the light theme and a lit one in the dark theme, and it costs no
 * asset, no network and no dependency.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onLogIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .padding(horizontal = Dimens.screenH),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(Dimens.xl))

        // The mark, at the size the launcher draws it: the app's own artwork - the
        // seal over the launcher's background - so the first thing on the screen is
        // the same thing the learner tapped to get here.
        //
        // The two layers are loaded separately rather than as the adaptive icon
        // itself, because an adaptive icon is neither a vector nor a bitmap and
        // cannot be painted as one.
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Image(
                painter = painterResource(R.drawable.ic_hanzi_icon),
                contentDescription = stringResource(R.string.welcome_logo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(Dimens.lg))

        Text(
            text = stringResource(R.string.app_title),
            style = MaterialTheme.typography.headlineMedium,
            color = AppTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(Dimens.xs))

        Text(
            text = stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(Dimens.md))

        // The illustration takes whatever height is left over. On a small phone it
        // shrinks, on a tall one it grows, and the two buttons below it never move
        // out of reach: the only thing that flexes is the picture.
        WelcomeIllustration(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = Dimens.sm)
        )

        PrimaryButton(
            text = stringResource(R.string.welcome_get_started),
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Dimens.sm))

        SecondaryButton(
            text = stringResource(R.string.welcome_log_in),
            onClick = onLogIn,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Dimens.xl))
    }
}

/**
 * Layered mountains and the Great Wall, drawn from the theme's colours.
 *
 * Four ridges recede into the distance, each a step deeper than the one behind it,
 * with a low sun over them and the wall running along the third ridge. The whole
 * thing is one hue at four opacities plus the ink, so it reads as a single
 * atmosphere rather than as a picture with its own palette, and it inverts with the
 * theme without a second drawing.
 */
@Composable
private fun WelcomeIllustration(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val palette = remember(colors) {
        IllustrationPalette(
            skyTop = colors.primaryAction.copy(alpha = 0.10f),
            skyBottom = colors.primaryAction.copy(alpha = 0f),
            sun = colors.vocabulary.copy(alpha = 0.32f),
            ridgeFar = colors.primaryAction.copy(alpha = 0.12f),
            ridgeMid = colors.primaryAction.copy(alpha = 0.20f),
            ridgeNear = colors.primaryAction.copy(alpha = 0.30f),
            ridgeFront = colors.primaryAction.copy(alpha = 0.44f),
            ink = colors.textPrimary.copy(alpha = 0.80f),
            inkSoft = colors.textPrimary.copy(alpha = 0.45f)
        )
    }
    val description = stringResource(R.string.welcome_illustration_description)

    Box(
        modifier = modifier.semantics { contentDescription = description }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawAtmosphere(palette)
            drawRidges(palette)
            drawGreatWall(palette)
            drawPines(palette)
        }
    }
}

/** The colours the illustration is drawn in, all resolved from the theme once. */
private data class IllustrationPalette(
    val skyTop: Color,
    val skyBottom: Color,
    val sun: Color,
    val ridgeFar: Color,
    val ridgeMid: Color,
    val ridgeNear: Color,
    val ridgeFront: Color,
    val ink: Color,
    val inkSoft: Color
)

/** The sky, and the low sun over it. */
private fun DrawScope.drawAtmosphere(palette: IllustrationPalette) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.skyTop, palette.skyBottom),
            startY = 0f,
            endY = size.height
        ),
        size = size
    )
    // A small sun with a soft halo, high and to the right. It is the one warm note in
    // a picture that is otherwise all one blue. The halo is a gradient rather than a
    // second circle: a flat disc of paler colour reads as a ring, not as light.
    val radius = size.minDimension * 0.070f
    val center = Offset(size.width * 0.76f, size.height * 0.19f)
    val halo = radius * 2.4f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(palette.sun.copy(alpha = 0.20f), Color.Transparent),
            center = center,
            radius = halo
        ),
        radius = halo,
        center = center
    )
    drawCircle(color = palette.sun, radius = radius, center = center)
}

/**
 * Four ridges, each a step deeper than the one behind it.
 *
 * Every ridge is filled all the way to the bottom of the picture, so they stack
 * like hills rather than sitting on shelves: a ridge that stopped at its own base
 * left a strip of bare sky between it and the one in front, and that strip read as
 * a mistake rather than as distance.
 */
private fun DrawScope.drawRidges(palette: IllustrationPalette) {
    val w = size.width
    val h = size.height

    val layers = listOf(
        Ridge(palette.ridgeFar, listOf(0.00f to 0.58f, 0.12f to 0.44f, 0.30f to 0.52f, 0.52f to 0.36f, 0.74f to 0.48f, 1.00f to 0.40f)),
        Ridge(palette.ridgeMid, listOf(0.00f to 0.66f, 0.16f to 0.52f, 0.38f to 0.60f, 0.62f to 0.46f, 0.84f to 0.56f, 1.00f to 0.50f)),
        Ridge(palette.ridgeNear, listOf(0.00f to 0.74f, 0.18f to 0.62f, 0.42f to 0.70f, 0.66f to 0.58f, 0.86f to 0.66f, 1.00f to 0.60f)),
        Ridge(palette.ridgeFront, listOf(0.00f to 0.90f, 0.22f to 0.79f, 0.52f to 0.85f, 0.78f to 0.77f, 1.00f to 0.83f))
    )

    layers.forEach { ridge ->
        val points = ridge.points.map { Offset(it.first * w, it.second * h) }
        drawPath(ridgePath(points, h, w), ridge.color)
    }
}

/** One ridge: the colour it is filled with and the peaks it runs through. */
private data class Ridge(
    val color: Color,
    val points: List<Pair<Float, Float>>
)

/**
 * The wall, running along the third ridge.
 *
 * A wall rather than a rope: a thin body, merlons standing on it, and a watchtower
 * where it turns. It is drawn in a softer ink than the foreground - a wall a
 * kilometre away is not the darkest thing in the picture, and drawing it as
 * though it were made the whole scene a cartoon.
 */
private fun DrawScope.drawGreatWall(palette: IllustrationPalette) {
    val w = size.width
    val h = size.height

    // The wall follows the third ridge, a little above its crest.
    val points = listOf(
        Offset(0.03f * w, 0.730f * h),
        Offset(0.18f * w, 0.610f * h),
        Offset(0.42f * w, 0.690f * h),
        Offset(0.66f * w, 0.570f * h),
        Offset(0.86f * w, 0.650f * h),
        Offset(0.97f * w, 0.600f * h)
    )

    val wall = Path()
    wall.moveTo(points.first().x, points.first().y)
    for (i in 0 until points.size - 1) {
        val current = points[i]
        val next = points[i + 1]
        wall.quadraticTo(
            current.x,
            current.y,
            (current.x + next.x) / 2f,
            (current.y + next.y) / 2f
        )
    }
    wall.quadraticTo(points.last().x, points.last().y, points.last().x, points.last().y)

    val wallInk = palette.ink.copy(alpha = 0.55f)
    drawPath(
        path = wall,
        color = wallInk,
        style = Stroke(width = h * 0.007f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Merlons: a small block every few steps along the wall, standing on it.
    val merlon = h * 0.012f
    for (i in 0 until points.size - 1) {
        val current = points[i]
        val next = points[i + 1]
        val steps = 4
        for (step in 1..steps) {
            val t = step.toFloat() / (steps + 1)
            val x = current.x + (next.x - current.x) * t
            val y = current.y + (next.y - current.y) * t
            drawRect(
                color = wallInk,
                topLeft = Offset(x - merlon * 0.4f, y - merlon),
                size = androidx.compose.ui.geometry.Size(merlon * 0.8f, merlon)
            )
        }
    }

    // A watchtower where the wall turns, so the wall has a scale reference. The roof
    // is a shallow trapezoid: a sharp triangle at this size reads as an arrow.
    val tower = points[3]
    val towerWidth = w * 0.026f
    val towerHeight = h * 0.040f
    drawRect(
        color = wallInk,
        topLeft = Offset(tower.x - towerWidth / 2f, tower.y - towerHeight),
        size = androidx.compose.ui.geometry.Size(towerWidth, towerHeight)
    )
    val roof = Path().apply {
        moveTo(tower.x - towerWidth * 0.9f, tower.y - towerHeight)
        lineTo(tower.x - towerWidth * 0.45f, tower.y - towerHeight - h * 0.010f)
        lineTo(tower.x + towerWidth * 0.45f, tower.y - towerHeight - h * 0.010f)
        lineTo(tower.x + towerWidth * 0.9f, tower.y - towerHeight)
        close()
    }
    drawPath(roof, wallInk)
}

/** A few pines on the nearest ridge, for scale. */
private fun DrawScope.drawPines(palette: IllustrationPalette) {
    val w = size.width
    val h = size.height
    val trees = listOf(
        Triple(0.13f, 0.815f, 1.0f),
        Triple(0.19f, 0.828f, 0.7f),
        Triple(0.86f, 0.795f, 0.9f)
    )
    for ((x, y, scale) in trees) {
        val height = h * 0.045f * scale
        val halfWidth = w * 0.013f * scale
        val tree = Path().apply {
            moveTo(x * w, y * h - height)
            lineTo(x * w - halfWidth, y * h)
            lineTo(x * w + halfWidth, y * h)
            close()
        }
        drawPath(tree, palette.inkSoft)
    }
}

/**
 * A ridge: a smooth line through [points], filled down to the bottom of the canvas.
 *
 * The peaks are joined with quadratics through their midpoints, which rounds a
 * jagged list into a ridge without ever rising above the peaks themselves. The line
 * runs the full width of the canvas - a ridge that stopped short of the edge left
 * a strip of bare background beside it, which read as a mistake rather than as a
 * horizon.
 */
private fun ridgePath(points: List<Offset>, baseY: Float, width: Float): Path {
    val path = Path()
    path.moveTo(0f, points.first().y)
    for (i in 0 until points.size - 1) {
        val current = points[i]
        val next = points[i + 1]
        val midX = (current.x + next.x) / 2f
        val midY = (current.y + next.y) / 2f
        path.quadraticTo(current.x, current.y, midX, midY)
    }
    path.quadraticTo(points.last().x, points.last().y, width, points.last().y)
    path.lineTo(width, baseY)
    path.lineTo(0f, baseY)
    path.close()
    return path
}
