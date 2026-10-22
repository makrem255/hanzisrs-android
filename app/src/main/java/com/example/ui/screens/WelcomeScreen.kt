package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * The artwork is a picture of the Great Wall at sunrise, framed by cherry blossoms,
 * packaged with the app. It is a picture rather than something drawn on the spot
 * because this is the one place the app is selling itself: the difference between
 * a painted approximation of a landscape and a photograph of one is the whole
 * first impression. It is a local asset, so it costs no network and works offline.
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

        // The mark, at the size the launcher draws it: the app's own artwork, the
        // same navy squircle with the cream brushstroke, the character, the sun,
        // the mountains and the pavilion that the learner tapped to get here. It
        // is the launcher icon's own artwork, shown whole so nothing in it is
        // cropped, and it is a single local asset so it costs no network and
        // works offline.
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = stringResource(R.string.welcome_logo_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(96.dp)
        )

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

        // The artwork takes whatever height is left over. On a small phone it
        // shrinks, on a tall one it grows, and the two buttons below it never move
        // out of reach: the only thing that flexes is the picture.
        //
        // The picture is portrait, and the space it is given is not always
        // portrait, so it is cropped rather than squeezed. The crop is taken from
        // the bottom and not from the middle: the top of the picture is the sky,
        // the blossoms and the sun, and the bottom is only the dark foreground
        // the wall comes out of. A shorter phone therefore loses the foreground
        // rather than the sky.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = Dimens.sm)
                .clip(RoundedCornerShape(24.dp))
        ) {
            Image(
                painter = painterResource(R.drawable.welcome_china_landscape),
                contentDescription = stringResource(R.string.welcome_illustration_description),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
            // A short fade into the page, so the picture ends in the app's own
            // background instead of in a hard edge - white in the light theme,
            // black in the dark one. It is the app's colour either way, so it
            // follows the theme without a second asset.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.12f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, AppTheme.colors.background)
                        )
                    )
            )
        }

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
