// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.stats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.ui.theme.SuyuM3Theme
import org.yuzu.yuzu_emu.utils.ArtworkColors
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.GameIconUtils
import org.yuzu.yuzu_emu.utils.applyPanelCloseMotion
import org.yuzu.yuzu_emu.utils.applyPanelWindowMotion

/**
 * "Your story": playtime, streaks, and most played games, coloured by the library's artwork.
 * The share summary carries aggregates only — never game titles — so it is safe to post.
 */
class PlaytimeStatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPanelWindowMotion()
        setContent {
            val artworkSeed by ArtworkColors.seed.collectAsState()
            SuyuM3Theme(artworkSeed = artworkSeed) {
                StoryScreen(onBack = { finish() })
            }
        }
    }

    override fun finish() {
        applyPanelCloseMotion()
        super.finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf(PlaytimeData()) }
    var library by remember { mutableStateOf<List<Game>>(emptyList()) }
    // Distinguish "no playtime yet" from "still reading the store"; otherwise the empty state
    // flashes for a frame on every open before the real numbers arrive.
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loadedData = withContext(Dispatchers.IO) {
            PlaytimeStore.read() to GameHelper.cachedGameList.toList()
        }
        data = loadedData.first
        library = loadedData.second
        loaded = true
    }

    val today = remember { LocalDate.now() }
    val total = totalPlaytime(data)
    val streak = playtimeStreak(data.days, today)
    val week = weekPlaytime(data.days, today)
    val top = topPlaytimeGames(data, 5)
    val maxSeconds = top.firstOrNull()?.second?.seconds ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.playtime_title), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_m3_arrow_back),
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { shareStory(context, total, data.games.size, streak) },
                        enabled = loaded && total > 0
                    ) { Text(stringResource(R.string.playtime_share)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!loaded) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (total == 0L) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        stringResource(R.string.playtime_empty),
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                HeroCard(total, data.games.size, totalSessions(data))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(Modifier.weight(1f), stringResource(R.string.playtime_streak), streak.toString())
                    StatCard(Modifier.weight(1f), stringResource(R.string.playtime_week), formatDuration(week))
                }
                Text(
                    stringResource(R.string.playtime_most_played),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // One container with dividers instead of a card per game: the ranking reads as a
                // single list and stops competing with the hero card above it.
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column {
                        top.forEachIndexed { index, entry ->
                            val (path, playtime) = entry
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 88.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            MostPlayedRow(
                                library.firstOrNull { it.path == path }, path, playtime, maxSeconds
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(totalSeconds: Long, gameCount: Int, sessions: Int) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                formatDuration(totalSeconds),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.playtime_hero_subtitle, gameCount, sessions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MostPlayedRow(game: Game?, path: String, playtime: GamePlaytime, maxSeconds: Long) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var cover by remember(game?.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(game?.path) {
        if (game != null) {
            cover = runCatching { GameIconUtils.getGameIcon(lifecycleOwner, game) }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.playtime_cover),
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            // A tonal tile with a vector glyph: tinting the raster default_icon turned the
            // whole photograph into a solid silhouette.
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_m3_sports_esports),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                game?.title ?: path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = {
                    if (maxSeconds > 0) playtime.seconds.toFloat() / maxSeconds else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        Text(
            formatDuration(playtime.seconds),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun formatDuration(seconds: Long): String = when {
    seconds >= 3600 ->
        stringResource(R.string.playtime_hours, String.format(Locale.getDefault(), "%.1f", seconds / 3600.0))
    seconds >= 60 -> stringResource(R.string.playtime_minutes, (seconds / 60).toString())
    seconds > 0 -> stringResource(R.string.playtime_under_minute)
    else -> stringResource(R.string.playtime_minutes, "0")
}

private fun shareStory(context: Context, totalSeconds: Long, gameCount: Int, streak: Int) {
    val hours = String.format(Locale.getDefault(), "%.1f", totalSeconds / 3600.0)
    val text = context.getString(R.string.playtime_share_text, hours, gameCount, streak)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
