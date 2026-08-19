package team.holder.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import team.holder.android.ui.theme.HolderTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snapshot = HolderNative.snapshot(
            dataDir = File(filesDir, "holder"),
            schemaSql = assets.open("schema.sql").bufferedReader().use { it.readText() },
            welcomeContent = assets.open("WELCOME.md").bufferedReader().use { it.readText() },
        )
        enableEdgeToEdge()
        setContent {
            HolderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HolderStatus(
                        snapshot = snapshot,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun HolderStatus(snapshot: HolderSnapshot, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Holder Android")
        Text(text = "libholder ${snapshot.coreVersion}")
        Text(text = snapshot.status)
        Text(text = "Projects: ${snapshot.projectCount}")
        Text(text = "Cards: ${snapshot.cardCount}")
    }
}

@Preview(showBackground = true)
@Composable
fun HolderStatusPreview() {
    HolderTheme {
        HolderStatus(
            HolderSnapshot(
                coreVersion = "0.1.7",
                projectCount = 1,
                cardCount = 2,
                status = "Opened native Holder store",
            )
        )
    }
}
