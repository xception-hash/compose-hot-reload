package dev.hotreload.multisample.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import dev.hotreload.multisample.core.coreLabel

/** Library composable with its own counter; renders a string sourced from :core. */
@Composable
fun FeatureCard() {
    var count by remember { mutableIntStateOf(0) }
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FeatureCard: ${coreLabel(count)}")
        Button(onClick = { count++ }) {
            Text("Feature count: $count")
        }
    }
}
