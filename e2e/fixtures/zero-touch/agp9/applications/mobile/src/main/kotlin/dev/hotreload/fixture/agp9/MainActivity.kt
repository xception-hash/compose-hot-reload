package dev.hotreload.fixture.agp9

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FixtureScreen() }
    }
}

@Composable
private fun FixtureScreen() {
    var count by remember { mutableIntStateOf(0) }
    Column {
        Greeting()
        Text("literal: v1")
        Button(onClick = { count++ }) {
            Text("Count: $count")
        }
    }
}

@Composable
private fun Greeting() {
    Text(transform(stringResource(R.string.fixture_label)))
}

private fun transform(value: String): String = value
