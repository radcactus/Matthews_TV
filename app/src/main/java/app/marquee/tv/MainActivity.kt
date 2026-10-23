package app.marquee.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.marquee.tv.data.Repo
import app.marquee.tv.ui.MarqueeApp
import app.marquee.tv.ui.MarqueeTheme

class MarqueeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MarqueeTheme { MarqueeApp() }
        }
    }
}
