package dev.sam3vision.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

/**
 * Point d'entree minimal. L'UI (Capture / Analyse / Chat / Reglages) est
 * implementee par un autre agent dans ce meme package : ne rien y ajouter ici
 * hormis le montage de l'ecran racine.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("placeholder")
                }
            }
        }
    }
}
