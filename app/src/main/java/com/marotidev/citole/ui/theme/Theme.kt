/*
Copyright (C) <2026>  <Balint Maroti>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

*/

package com.marotidev.citole.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

enum class CitoleThemeMode(val id: Int) { System(0), Light(1), Dark(2) }
enum class CitoleColorSource(val id: Int) { AlbumArt(0), SystemDynamic(1), Custom(2) }

fun paletteStyleFromOrdinal(ord: Int): PaletteStyle = when (ord) {
    1 -> PaletteStyle.Vibrant
    2 -> PaletteStyle.Expressive
    3 -> PaletteStyle.Neutral
    4 -> PaletteStyle.FruitSalad
    5 -> PaletteStyle.Rainbow
    6 -> PaletteStyle.Monochrome
    else -> PaletteStyle.TonalSpot
}
fun paletteName(style: PaletteStyle): String = when (style) {
    PaletteStyle.TonalSpot -> "Tonal Spot"
    PaletteStyle.Vibrant -> "Vibrant"
    PaletteStyle.Expressive -> "Expressive"
    PaletteStyle.Neutral -> "Neutral"
    PaletteStyle.FruitSalad -> "Fruit Salad"
    PaletteStyle.Rainbow -> "Rainbow"
    PaletteStyle.Monochrome -> "Monochrome"
    else -> style.toString()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DynamicAppTheme(
    seedColor: Color,
    themeMode: Int = 0,
    paletteStyleOrdinal: Int = 0,
    isBlackTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    DynamicMaterialExpressiveTheme(
        seedColor = seedColor,
        style = paletteStyleFromOrdinal(paletteStyleOrdinal),
        isDark = darkTheme,
        isAmoled = isBlackTheme && darkTheme,
        animate = true,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        content = content,
        specVersion = if (isBlackTheme && darkTheme) ColorSpec.SpecVersion.SPEC_2021 else ColorSpec.SpecVersion.SPEC_2025,
        typography = Typography,
    )
}

object M3ExpressiveTransitions {

    const val WIDTH = 0.25f

    private val SpatialSpring = spring(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = 0.82f,
        visibilityThreshold = IntOffset.VisibilityThreshold
    )

    private val FadeSpring = spring<Float>(
        stiffness = Spring.StiffnessMedium,
        dampingRatio = Spring.DampingRatioNoBouncy
    )

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            initialOffsetX = { fullWidth -> (fullWidth * WIDTH).toInt() },
            animationSpec = SpatialSpring
        ) + fadeIn(animationSpec = FadeSpring)
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -(fullWidth * WIDTH).toInt() },
            animationSpec = SpatialSpring
        ) + fadeOut(animationSpec = FadeSpring)
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -(fullWidth * WIDTH).toInt() },
            animationSpec = SpatialSpring
        ) + fadeIn(animationSpec = FadeSpring)
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> (fullWidth * WIDTH).toInt() },
            animationSpec = SpatialSpring
        ) + fadeOut(animationSpec = FadeSpring)
    }
}
