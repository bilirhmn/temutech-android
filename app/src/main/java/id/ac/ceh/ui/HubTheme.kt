package id.ac.ceh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.ac.ceh.R

private fun hubText(size: Int, height: Int, weight: FontWeight, spacing: Float = 0f) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
    lineHeight = height.sp, letterSpacing = spacing.sp
)

private val hubTypography = Typography(
    displaySmall = hubText(32, 40, FontWeight.Bold),
    headlineMedium = hubText(28, 36, FontWeight.Bold),
    titleLarge = hubText(22, 28, FontWeight.SemiBold),
    titleMedium = hubText(18, 24, FontWeight.SemiBold),
    bodyLarge = hubText(16, 24, FontWeight.Normal),
    bodyMedium = hubText(14, 20, FontWeight.Normal),
    labelLarge = hubText(14, 20, FontWeight.SemiBold, 0.1f),
    labelSmall = hubText(12, 16, FontWeight.Medium, 0.1f)
)

@Composable
internal fun HubTheme(content: @Composable () -> Unit) {
    // Resolve the same day/night resources used by the Fragment host.
    val defaults = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val background = colorResource(R.color.hub_background)
    val surface = colorResource(R.color.hub_surface)
    val onSurface = colorResource(R.color.hub_on_surface)
    val variant = colorResource(R.color.hub_surface_variant)
    val primary = colorResource(R.color.hub_primary)
    val colors = defaults.copy(
        primary = primary,
        onPrimary = colorResource(R.color.hub_on_primary),
        primaryContainer = colorResource(R.color.hub_primary_container),
        onPrimaryContainer = colorResource(R.color.hub_on_primary_container),
        secondary = colorResource(R.color.hub_secondary),
        onSecondary = colorResource(R.color.hub_on_secondary),
        secondaryContainer = colorResource(R.color.hub_secondary_container),
        onSecondaryContainer = colorResource(R.color.hub_on_secondary_container),
        background = background, onBackground = onSurface,
        surface = surface, onSurface = onSurface, surfaceTint = primary,
        surfaceVariant = variant, onSurfaceVariant = colorResource(R.color.hub_on_surface_variant),
        surfaceContainer = variant, surfaceContainerLow = background, surfaceContainerLowest = surface,
        outline = colorResource(R.color.hub_outline),
        error = colorResource(R.color.hub_error), onError = colorResource(R.color.hub_on_error),
        errorContainer = colorResource(R.color.hub_error_container),
        onErrorContainer = colorResource(R.color.hub_on_error_container),
    )
    MaterialTheme(
        colorScheme = colors, typography = hubTypography,
        shapes = Shapes(
            small = RoundedCornerShape(dimensionResource(R.dimen.hub_radius_control)),
            medium = RoundedCornerShape(dimensionResource(R.dimen.hub_radius_card)),
            large = RoundedCornerShape(dimensionResource(R.dimen.hub_radius_dialog)),
        ),
        content = content,
    )
}
