package com.marotidev.citole.presentation.settings.appearance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.marotidev.citole.R
import com.marotidev.citole.ui.theme.CitoleColorSource
import com.marotidev.citole.ui.theme.CitoleThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.harmonize

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    navController: NavController,
    vm: AppearanceViewModel = hiltViewModel()
) {
    val themeMode by vm.themeMode.collectAsState()
    val colorSource by vm.colorSource.collectAsState()
    val customColor by vm.customColor.collectAsState()
    val paletteStyle by vm.paletteStyle.collectAsState()
    val blackTheme by vm.useBlackTheme.collectAsState()
    val haptic = LocalHapticFeedback.current

    val paletteOptions = listOf(
        PaletteStyle.TonalSpot to "Tonal Spot",
        PaletteStyle.Vibrant to "Vibrant",
        PaletteStyle.Expressive to "Expressive",
        PaletteStyle.Neutral to "Neutral",
        PaletteStyle.FruitSalad to "Fruit Salad",
        PaletteStyle.Rainbow to "Rainbow",
        PaletteStyle.Monochrome to "Mono"
    )
    val customPresets = listOf(
        0xFF3FDAEE.toInt(), 0xFFFF6B6B.toInt(), 0xFF8B5CF6.toInt(), 0xFF10B981.toInt(),
        0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF06B6D4.toInt(), 0xFF6366F1.toInt(),
        0xFFEC4899.toInt(), 0xFF84CC16.toInt(), 0xFFF97316.toInt(), 0xFF14B8A6.toInt()
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Row(modifier = Modifier.statusBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = { navController.popBackStack() },
                    shapes = IconButtonDefaults.shapes(shape = CircleShape, pressedShape = MaterialTheme.shapes.medium),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.padding(start = 18.dp, end = 12.dp)
                ) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Text("THEME", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            }
            item {
                SegmentedListItem(
                    modifier = Modifier.padding(vertical = 1.dp),
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
                    contentPadding = PaddingValues(16.dp),
                    content = { Text("Theme mode", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(when(themeMode){1->"Light always";2->"Dark always";else->"Follow system"}, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFfcbd00)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_palette), null, tint = Color(0xFF6d3a01), modifier = Modifier.size(22.dp))
                        }
                    },
                    onClick = {}
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CitoleThemeMode.System to "System", CitoleThemeMode.Light to "Light", CitoleThemeMode.Dark to "Dark").forEach { (mode, label) ->
                        val selected = themeMode == mode.id
                        ToggleButton(
                            checked = selected,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.setThemeMode(mode.id)
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shapes = ToggleButtonShapes(
                                shape = CircleShape,
                                pressedShape = MaterialTheme.shapes.small,
                                checkedShape = CircleShape
                            ),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                checkedContainerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            item {
                Text("COLOR", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        Triple(CitoleColorSource.AlbumArt.id, "Album Art", "Dynamic from now-playing cover — Citole signature"),
                        Triple(CitoleColorSource.SystemDynamic.id, "System", "Material You dynamic color"),
                        Triple(CitoleColorSource.Custom.id, "Custom", "Pick your own accent")
                    ).forEachIndexed { idx, (id, title, sub) ->
                        val selected = colorSource == id
                        SegmentedListItem(
                            modifier = Modifier.padding(vertical = 1.dp),
                            shapes = ListItemDefaults.segmentedShapes(index = idx, count = 3),
                            contentPadding = PaddingValues(16.dp),
                            content = { Text(title, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = { Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingContent = {
                                val bg = when(id){
                                    0 -> Color(0xFF3FDAEE).harmonize(MaterialTheme.colorScheme.primary, true)
                                    1 -> Color(0xFF85B7FA).harmonize(MaterialTheme.colorScheme.primary, true)
                                    else -> Color(customColor)
                                }
                                Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(when(id){0->R.drawable.ic_wand_stars;1->R.drawable.ic_palette;else->R.drawable.ic_verified}), null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            },
                            trailingContent = {
                                Switch(checked = selected, onCheckedChange = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.setColorSource(id)
                                })
                            },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.setColorSource(id)
                            }
                        )
                    }
                }
            }

            item {
                AnimatedVisibility(visible = colorSource == CitoleColorSource.Custom.id) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text("Accent presets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            itemsIndexed(customPresets) { _, col ->
                                val isSel = customColor == col
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(col))
                                        .border(width = if (isSel) 3.dp else 0.dp, color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent, shape = CircleShape)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.setCustomColor(col)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSel) Icon(painterResource(R.drawable.ic_check), null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("PALETTE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    itemsIndexed(paletteOptions) { idx, (_, name) ->
                        val selected = paletteStyle == idx
                        ToggleButton(
                            checked = selected,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.setPaletteStyle(idx)
                            },
                            shapes = ToggleButtonShapes(shape = RoundedCornerShape(16.dp), pressedShape = RoundedCornerShape(8.dp), checkedShape = RoundedCornerShape(24.dp)),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) { Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp)) }
                    }
                }
            }

            item {
                Text("DISPLAY", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            }
            item {
                SegmentedListItem(
                    modifier = Modifier.padding(vertical = 1.dp),
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                    contentPadding = PaddingValues(16.dp),
                    content = { Text("Black theme (AMOLED)", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text("Pure black background in dark mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_graphic_eq), null, modifier = Modifier.size(20.dp))
                        }
                    },
                    trailingContent = {
                        Switch(checked = blackTheme, onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.setBlackTheme(it)
                        })
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.setBlackTheme(!blackTheme)
                    }
                )
            }

            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp)
                ) {
                    Column {
                        Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_music), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("Citole", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("Now playing adapts instantly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                            }
                        }
                    }
                }
            }
        }
    }
}
