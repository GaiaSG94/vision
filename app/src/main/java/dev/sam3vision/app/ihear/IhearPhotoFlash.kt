package dev.sam3vision.app.ihear

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.decodeScaledExact
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le flash photo d'iHear, posé sur la page caméra : la photo du double tap apparaît
 * au centre (nouveaux clichés d'une rafale remplacés à chaque fois), reste quelques
 * secondes, puis s'évapore — la disparition est portée par l'état [IhearUiState.photoPath],
 * vidé côté [IhearViewModel] après le délai de [IhearViewModel.PHOTO_SHOW_MS].
 */
@Composable
fun IhearPhotoFlash(photoPath: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = photoPath,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "photo-flash",
        ) { path ->
            if (path != null) {
                val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
                    value = withContext(Dispatchers.IO) { decodeScaledExact(File(path)) }
                }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.82f)
                            .padding(vertical = 24.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .border(3.dp, Color.White, RoundedCornerShape(28.dp)),
                    )
                }
            }
        }
    }
}
