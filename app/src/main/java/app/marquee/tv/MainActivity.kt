package app.marquee.tv

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.marquee.tv.data.MetaPreview
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
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            MarqueeTheme { MarqueeApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** matthewstv://detail/{type}/{id} from the TV home screen opens that title. */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "matthewstv" || uri.host != "detail") return
        val seg = uri.pathSegments
        if (seg.size < 2) return
        val type = seg[0]
        val id = seg[1]
        val p = Repo.progress.value[id]
        Repo.deepLink.value = MetaPreview(id = id, type = type, name = p?.name.orEmpty(), poster = p?.poster)
    }
}
