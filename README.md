# Matthews TV

A fast, native Android TV front-end for your Stremio account. Runs on Google TV and Fire TV.

It uses your existing Stremio account: the same addons, the same library, the same watch history. Marquee replaces the browsing and recommendations; Stremio stays the source of truth.

## What's in it

- **Home** with a big backdrop and synopsis for whatever is focused, then rows:
  - Continue watching (including "Up next" for the next episode after you finish one)
  - Picked for you (scored from the genres you actually watch, recent titles count more, already-watched titles are hidden)
  - Because you watched *X* (needs a free TMDB key, see Settings)
  - Top *genre* for you rows for your strongest genres
  - Your addons' catalogs (hide any you don't want in Settings)
  - Your library
- **Browse**: Movies or Series, any addon catalog, genre filter, endless grid.
- **Search** across every addon that supports it.
- **Library** with movie/series filters and three sort orders.
- **Detail page**: resume button, season picker, episode list with progress, "More like this".
- **Streams panel** that tests every link as it arrives: working links (with file size and response time) rise to the top, broken ones are hidden behind a "Show broken links" button, and focus lands on the first link that works. It catches dead hosts, expired links, error pages, and the short "downloading…/failed" placeholder clips debrid addons hand out. Torrent links are judged by their seeder count. Links that fail in the player are remembered as broken.
  Playable links are sorted best picture first (2160p, 1080p, 720p…), then by compression within each resolution (AV1, HEVC, VP9, H.264, older codecs), then by source (Remux, BluRay, WEB-DL, WEBRip, HDTV). Camera recordings go to the bottom. Marquee checks what your TV can actually do: on a 1080p TV a 4K link ranks the same as 1080p, and codecs the TV has no hardware decoder for are moved down and flagged.
- **Built-in player** (ExoPlayer) with resume, subtitle/audio track picker, D-pad seek (left 10s, right 30s, OK to pause). Progress is saved locally every 15s and synced back to Stremio when you stop.
- **Players**: VLC's engine is built into the app and is the default, so nothing opens another app. It plays almost any file (MKV, HEVC, AV1, DTS/TrueHD audio, embedded subtitles), with remote-friendly controls, audio and subtitle pickers, resume and progress sync. ExoPlayer is built in as a second engine; if one can't start a file the other is tried automatically. Long-press a stream to pick an engine just for that one.
- **Fallback**: "Open in Stremio" for anything Marquee can't play itself.

### Which streams play inside Marquee

| Stream type | What happens |
|---|---|
| Direct links (HTTP/HLS/DASH, e.g. from debrid addons) | Plays in Marquee |
| Torrent-based (`infoHash`) | Plays through a Stremio streaming server if you set one in Settings, otherwise opens in Stremio |
| YouTube / external links | Open in the matching app |

## Getting the APK

### Option A: GitHub builds it for you (no tools needed)

1. Create a GitHub repository and push this folder to it (branch `main`).
2. The **Build APK** workflow runs automatically (Actions tab, about 5 minutes).
3. The APK is published at
   `https://github.com/<you>/<repo>/releases/latest/download/matthews-tv.apk`
   (public repos), and as a download on the workflow run page.

Every push builds a new version that installs over the old one.

### Option B: Android Studio

Open the folder in Android Studio, let it sync, then **Build → Build APK(s)**. Or from a terminal: `./gradlew assembleRelease`; the APK lands in `app/build/outputs/apk/release/`.

## Installing on your TVs

**Google TV**
1. Install **Downloader** (by AFTVnews) from the Play Store.
2. Turn on developer mode: Settings → System → About → click **Android TV OS build** 7 times.
3. Settings → Apps → Security & restrictions → Unknown sources → turn on **Downloader**.
4. Open Downloader, enter the APK URL from above, install.

**Fire TV**
1. Install **Downloader** from the Amazon Appstore.
2. Settings → My Fire TV → Developer options → Install unknown apps → turn on **Downloader**.
   (If Developer options is missing: Settings → My Fire TV → About → click the device name 7 times.)
3. Open Downloader, enter the APK URL, install.

Marquee then appears in your apps row. Long-press it to move it to the front.

## Settings worth knowing

- **TMDB key** (free from themoviedb.org → Settings → API): unlocks "Because you watched" rows and better "More like this".
- **Streaming server**: e.g. `http://192.168.1.20:11470` if you run Stremio's server on a computer at home.
- **Sync watch progress**: on by default. Only updates titles that are already in your Stremio library; it never adds or removes items.

## Project layout

```
app/src/main/java/app/marquee/tv/
  data/        Stremio account API, addon protocol, TMDB, recommendations, local progress
  ui/          Compose for TV screens (Home, Browse, Search, Library, Detail, Streams, Settings, Login)
  PlayerActivity.kt   ExoPlayer playback and progress saving
  Playback.kt         Decides how each stream is played
```

## Signing and security

Release APKs are signed with a private key stored only in this repo's GitHub Actions secrets (`SIGNING_KEY`, the keystore in base64, and `SIGNING_PASSWORD`). The key is never in the code, so the repo can be public: nobody can build an APK your TVs would accept as an update to this app. Keep a copy of the key somewhere safe. If it's lost, a new key works, but each TV has to uninstall and reinstall once.

Your Stremio password is sent only to Stremio and never stored. The sign-in token and any TMDB key are stored only on each TV, and the app opts out of Android backups so they don't leave the device.
