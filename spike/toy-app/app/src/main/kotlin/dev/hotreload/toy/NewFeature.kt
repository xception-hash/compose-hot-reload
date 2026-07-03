package dev.hotreload.toy

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Experiment D: this whole file is absent from the installed APK. Its classes are
// injected at runtime via the ART add_to_dex_class_loader_in_memory extension,
// then the (redefined) Greeting starts calling into it.
@Composable
fun NewBanner() {
    Text("Banner from a class injected at runtime!")
}
