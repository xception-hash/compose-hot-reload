package dev.hotreload.capture.feature

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun CaptureCard() {
    var count by remember { mutableIntStateOf(0) }
    val increment: () -> Unit = { count++ }

    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(1)) {
        captureItem(count, increment)
    }
}

private fun LazyStaggeredGridScope.captureItem(count: Int, increment: () -> Unit) {
    item {
        Text("Capture baseline: $count")
        Button(onClick = increment) {
            Text("Capture count: $count")
        }
    }
}
