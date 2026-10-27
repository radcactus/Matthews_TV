package app.marquee.tv

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.marquee.tv.data.MetaPreview
import app.marquee.tv.data.Http
import app.marquee.tv.data.Repo
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import app.marquee.tv.ui.MarqueeApp
import app.marquee.tv.ui.MarqueeTheme

class MarqueeApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        Repo.init(this)
    }

    /** Posters and backdrops: shared connections, a quarter of memory, 300 MB on disk. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
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
