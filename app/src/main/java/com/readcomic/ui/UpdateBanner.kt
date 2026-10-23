package com.readcomic.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.readcomic.update.AppUpdater
import com.readcomic.update.UpdateOffer
import java.util.Locale

/** Updates of the app, and what to call when the reader acts on them. */
class UpdateUi(
    val state: AppUpdater.State = AppUpdater.State.Idle,
    /** Looking for updates by itself, once a day. */
    val enabled: Boolean = true,
    val currentBuild: Long = 0,
    /** This is the preview build, "Readcomic β". */
    val preview: Boolean = false,
    /** The reader asked to look for an update, and the answer is on its way: the banner says so. */
    val checkingByHand: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit = {},
    val onCheckNow: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onPutOff: () -> Unit = {},
    /** Hands the downloaded build to Android's installer, from this activity. */
    val onInstall: (Context) -> Unit = {},
    /** Android already lets the app install updates (else it asks once). */
    val canInstall: () -> Boolean = { true },
) {
    /** Something to show on the banner. */
    val shown: Boolean
        get() = when (val s = state) {
            is AppUpdater.State.Available, is AppUpdater.State.Downloading, is AppUpdater.State.Ready -> true
            is AppUpdater.State.Failed -> s.offer != null
            AppUpdater.State.Checking -> checkingByHand
            else -> false
        }
}

/**
 * Once the update is downloaded, Android's installer comes up by itself (once; after that, from
 * the banner), only while the app is on screen.
 */
@Composable
fun UpdateInstallEffect(ui: UpdateUi) {
    val ready = ui.state as? AppUpdater.State.Ready
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentUi by rememberUpdatedState(ui)
    LaunchedEffect(ready) {
        if (ready?.launch != true) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            currentUi.onInstall(context)
        }
    }
}

/** A newer build: what is new, downloading it, installing it. */
@Composable
fun UpdateBanner(ui: UpdateUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = ui.state
    if (state == AppUpdater.State.Checking) {
        if (ui.checkingByHand) CheckingBanner(ui, modifier)
        return
    }
    val offer: UpdateOffer = when (state) {
        is AppUpdater.State.Available -> state.offer
        is AppUpdater.State.Downloading -> state.offer
        is AppUpdater.State.Ready -> state.offer
        is AppUpdater.State.Failed -> state.offer ?: return
        else -> return
    }
    var expanded by remember(offer.build) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xF21B1713),
        contentColor = BannerInk,
        shadowElevation = 10.dp,
        modifier = modifier,
    ) {
        Column(Modifier.animateContentSize().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(
                        when (state) {
                            is AppUpdater.State.Downloading -> "Скачиваю обновление"
                            is AppUpdater.State.Ready -> "Обновление скачано"
                            is AppUpdater.State.Failed -> "Не получилось обновить"
                            else -> "Доступно обновление"
                        },
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Text(
                        when (state) {
                            is AppUpdater.State.Downloading -> "Сборка ${offer.build} · ${(state.progress * 100).toInt()}%"
                            is AppUpdater.State.Ready -> "Сборка ${offer.build} — осталось установить"
                            is AppUpdater.State.Failed -> state.reason
                            else -> "Сборка ${offer.build}" + sizeText(offer.size) + " · сейчас ${ui.currentBuild}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BannerInk.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state !is AppUpdater.State.Downloading) {
                    IconButton(onClick = ui.onPutOff) {
                        Icon(Icons.Filled.Close, contentDescription = "Не сейчас", tint = BannerInk.copy(alpha = 0.7f))
                    }
                }
            }
            val notes = offer.notes
            if (notes != null && state is AppUpdater.State.Available) {
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = BannerInk.copy(alpha = 0.85f),
                    maxLines = if (expanded) 14 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp, end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = if (expanded) "Свернуть" else "Что нового") { expanded = !expanded },
                )
            }
            if (state is AppUpdater.State.Downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).height(4.dp).clip(RoundedCornerShape(50)),
                    color = BannerInk,
                    trackColor = Color.White.copy(alpha = 0.14f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
            if ((state is AppUpdater.State.Available || state is AppUpdater.State.Ready) && !ui.canInstall()) {
                Text(
                    "Android один раз спросит, можно ли Readcomic устанавливать обновления: включи и вернись назад.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BannerInk.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is AppUpdater.State.Downloading -> BannerTextButton("Отмена", ui.onCancel)
                    is AppUpdater.State.Ready -> BannerButton("Установить") { ui.onInstall(context) }
                    is AppUpdater.State.Failed -> {
                        BannerButton("Ещё раз", ui.onDownload)
                        BannerTextButton("Позже", ui.onPutOff)
                    }
                    else -> {
                        BannerButton("Обновить", ui.onDownload)
                        BannerTextButton("Позже", ui.onPutOff)
                    }
                }
            }
        }
    }
}

/** Looking for an update, as the reader asked: what it is doing, until GitHub answers. */
@Composable
private fun CheckingBanner(ui: UpdateUi, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xF21B1713),
        contentColor = BannerInk,
        shadowElevation = 10.dp,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            CircularProgressIndicator(color = BannerInk, trackColor = Color.White.copy(alpha = 0.14f), strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Проверяю обновления…", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Сейчас стоит сборка ${ui.currentBuild}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BannerInk.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun BannerButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = BannerInk, contentColor = Color(0xFF2A1F17)),
        modifier = Modifier.height(42.dp),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun BannerTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(42.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = BannerInk.copy(alpha = 0.8f))
    }
}

/** " · 9,7 МБ", or nothing when the size is not known. */
private fun sizeText(bytes: Long): String =
    if (bytes <= 0) "" else " · " + String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / (1024f * 1024f))

private val BannerInk = Color(0xFFF3E6D0)
