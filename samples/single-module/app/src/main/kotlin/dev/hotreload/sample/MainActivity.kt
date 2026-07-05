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

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Counter()
        Greeting()
        LiteralLabel()
        ResourceLabel()
        HotIcon()
        HotPhoto()
        Button(
            onClick = {
                context.startActivity(Intent(context, SecondActivity::class.java))
            },
            modifier = Modifier.semantics { contentDescription = "OPEN_SECOND" },
        ) {
            Text("Open second")
        }
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
fun HotPhoto() {
    Image(
        painter = painterResource(R.drawable.hot_photo),
        contentDescription = "HOT_PHOTO",
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

// Live-literals fast-path testbed (T24): the "literal: vN" string is a plain string
// literal, so with -Photreload.liveLiterals=true it compiles to a LiveLiterals$ helper
// the `--literals` watch path can update in place. e2e case 14 edits v1 -> v2.
@Composable
fun LiteralLabel() {
    Text("literal: v1")
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
