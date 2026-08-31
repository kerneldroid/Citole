package com.marotidev.citole.presentation.settings.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.marotidev.citole.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    val pkg = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val ver = pi.versionName ?: "0.3.4"
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
            "$ver ($code)"
        } catch (_: Exception) { "0.3.4 (39)" }
    }

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
                Text("About", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Hero plaque - Citole branding
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.ic_citole_icon), contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(8.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Citole", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Lightweight M3 Expressive Offline Player", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    Spacer(Modifier.height(8.dp))
                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kerneldroid/Citole"))
                            context.startActivity(intent)
                        }) {
                            Icon(painterResource(R.drawable.ic_info), null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub")
                        }
                        FilledTonalButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kerneldroid/Citole/issues"))
                            context.startActivity(intent)
                        }) {
                            Icon(painterResource(R.drawable.ic_book), null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Report")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("INFO", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))

            SegmentedListItem(
                modifier = Modifier.padding(vertical = 1.dp),
                shapes = ListItemDefaults.segmentedShapes(0, 4),
                contentPadding = PaddingValues(16.dp),
                headlineContent = { Text("Developer", style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text("Balint Maroti — GPLv3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_person), null, modifier = Modifier.size(20.dp))
                    }
                },
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kerneldroid")))
                }
            )
            SegmentedListItem(
                modifier = Modifier.padding(vertical = 1.dp),
                shapes = ListItemDefaults.segmentedShapes(1, 4),
                contentPadding = PaddingValues(16.dp),
                headlineContent = { Text("License", style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text("GNU GPL v3 — open source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_page_info), null, modifier = Modifier.size(20.dp))
                    }
                },
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html")))
                }
            )
            SegmentedListItem(
                modifier = Modifier.padding(vertical = 1.dp),
                shapes = ListItemDefaults.segmentedShapes(2, 4),
                contentPadding = PaddingValues(16.dp),
                headlineContent = { Text("Built with", style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text("Compose · M3 Expressive · Media3 · Room · Hilt · Coil", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_wand_stars), null, modifier = Modifier.size(20.dp))
                    }
                }
            )
            SegmentedListItem(
                modifier = Modifier.padding(vertical = 1.dp),
                shapes = ListItemDefaults.segmentedShapes(3, 4),
                contentPadding = PaddingValues(16.dp),
                headlineContent = { Text("Version", style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_info), null, modifier = Modifier.size(20.dp))
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Citole is a lightweight offline audio player. No ads, no tracking — just your music.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
