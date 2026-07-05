package dev.hotreload.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Counter()
        Greeting()
        ResourceLabel()
        HotIcon()
        ItemList()
    }
}

@Composable
fun ResourceLabel() {
    Text(stringResource(R.string.hot_label))
}

@Composable
fun HotIcon() {
    Image(
        painter = painterResource(R.drawable.hot_icon),
        contentDescription = "HOT_ICON",
        modifier = Modifier.size(48.dp),
    )
}

@Composable
fun Counter() {
    var count by remember { mutableIntStateOf(0) }
    var saved by rememberSaveable { mutableIntStateOf(0) }
    Button(onClick = { count++; saved++ }) {
        Text("Count: $count / Saved: $saved")
    }
}

@Composable
fun Greeting() {
    Text("Hello from the sample app")
}

@Composable
fun ItemList() {
    val items = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")
    LazyColumn {
        items(items) { item ->
            Text(item)
        }
    }
}
