package dev.hotreload.fixture.agp8

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hotreload.fixture.agp8.feature.FeatureCard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var count by remember { mutableIntStateOf(0) }
            Column {
                FeatureCard()
                Button(onClick = { count++ }) {
                    Text("Count: $count")
                }
            }
        }
    }
}
