package dev.hotreload.multisample.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.hotreload.multisample.core.coreLabel

/** Library composable with its own counter; renders a string sourced from :core. */
@Composable
fun FeatureCard() {
    var count by remember { mutableIntStateOf(0) }
    val increment: () -> Unit = { count++ }
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.feature_label))
        // Keep this deliberately capture-heavy: the lazy item lambda closes over the state-backed
        // count and a separately-created callback. The configured library hot-swap regression
        // exercises a body-only edit below without corrupting either captured value.
        LazyColumn {
            items(listOf("feature")) {
                Text("FeatureCard: ${coreLabel(count)}")
                Button(onClick = increment) {
                    Text("Feature count: $count")
                }
            }
        }
    }
}
