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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HolderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HolderStatus(
                        coreVersion = HolderNative.version(),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun HolderStatus(coreVersion: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Holder Android")
        Text(text = "libholder $coreVersion")
    }
}

@Preview(showBackground = true)
@Composable
fun HolderStatusPreview() {
    HolderTheme {
        HolderStatus("0.1.2")
    }
}
