/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.ui.picker

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.axion.compose.preferences.ClickablePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.device.settings.R
import org.lineageos.device.settings.ui.PrefIcon

private const val PREVIEW_PX = 192

/**
 * Loads one drawable of resource package [pkg] as a small static bitmap. Large and animated
 * images are decoded straight to [PREVIEW_PX] (first frame) so a full pack grid stays cheap.
 */
private fun loadPackPreview(context: Context, pkg: String, name: String): ImageBitmap? = try {
    val res = context.packageManager.getResourcesForApplication(pkg)
    val id = res.getIdentifier(name, "drawable", pkg)
    if (id == 0) {
        null
    } else {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(res, id)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val scale = PREVIEW_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) {
            // Vector or other non-image drawable.
            res.getDrawable(id, null)?.let { d ->
                Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888).also {
                    d.setBounds(0, 0, PREVIEW_PX, PREVIEW_PX)
                    d.draw(Canvas(it))
                }
            }
        }
        bitmap?.asImageBitmap()
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: Exception) {
    null
}

/**
 * Preference that opens a dialog showing every choice of a resource pack as a picture grid.
 *
 * @param options key/label pairs, index aligned with [previews]
 * @param previews drawable name of each option inside [pkg], or null for no picture
 */
@Composable
internal fun PackGridPreference(
    title: String,
    pkg: String,
    options: List<Pair<String, String>>,
    previews: List<String?>,
    value: String,
    onValueChange: (String) -> Unit,
    @DrawableRes icon: Int,
) {
    var show by remember { mutableStateOf(false) }
    ClickablePreference(
        title = title,
        summary = options.firstOrNull { it.first == value }?.second,
        customIcon = { PrefIcon(icon) },
        onClick = { show = true },
    )
    if (show) {
        PackGridDialog(
            title = title,
            pkg = pkg,
            options = options,
            previews = previews,
            selectedKey = value,
            onPick = {
                onValueChange(it)
                show = false
            },
            onDismiss = { show = false },
        )
    }
}

@Composable
private fun PackGridDialog(
    title: String,
    pkg: String,
    options: List<Pair<String, String>>,
    previews: List<String?>,
    selectedKey: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val images by produceState(initialValue = emptyMap<Int, ImageBitmap>(), pkg, previews) {
        withContext(Dispatchers.IO) {
            val loaded = HashMap<Int, ImageBitmap>()
            previews.forEachIndexed { i, name ->
                if (name != null) {
                    loadPackPreview(context, pkg, name)?.let { loaded[i] = it }
                    // Publish progressively so the grid fills in while decoding.
                    if (i % 8 == 7) value = HashMap(loaded)
                }
            }
            value = loaded
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(options, key = { _, o -> o.first }) { index, option ->
                    val selected = option.first == selectedKey
                    val shape = RoundedCornerShape(16.dp)
                    Column(
                        modifier = Modifier
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onPick(option.first) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            val image = images[index]
                            if (image != null) {
                                Image(
                                    bitmap = image,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                        Text(
                            text = option.second,
                            fontSize = 11.sp,
                            maxLines = 2,
                            minLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
