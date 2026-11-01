# Leatherbound Listening

A self-made audiobook player for Plex, Jellyfin and Audiobookshelf, with a bound-book look: parchment pages, oxblood leather, gilt rules, and Libre Baskerville type.

## Features

| | |
|---|---|
| **Sources** | Plex (sign in with your Plex account, or enter a URL + token), Jellyfin (username/password), Audiobookshelf (username/password), and folders on the device (Storage Access Framework) |
| **Library** | Metadata, chapters and covers are cached in a local Room database. Sort by recently added, recently played, title, author (surname-aware), year, series, length, genre (genre shows a card per genre, with a filter for which genres appear). Filter by unread, in progress, finished, downloaded. Grid or list. |
| **Chapters** | Plex embedded chapters, Jellyfin chapters, M4B chapters on local files (Nero `chpl` and QuickTime chapter tracks), or one chapter per file. |
| **Playback** | Media3/ExoPlayer in a foreground service. Skip back and forward 15 s, crossing file boundaries. Resuming after a pause rewinds 5 s (adjustable 0–30 s), and opening the app rewinds the last book 5 s. Speeds from 0.75× to 2.5×. |
| **System controls** | Notification, lock screen, Android Auto, steering-wheel and headphone controls. Previous/next jump back and forward through the book (30 s each by default; set them in Settings → Android Auto). |
| **Android Auto** | Browse Continue, Listening list, Library and Downloaded as cover grids. Voice search ("play *title*") works. |
| **Offline** | Download whole books from Plex/Jellyfin through WorkManager (Wi-Fi only by default). Downloaded files play first. |
| **Series** | Plex and Jellyfin collections are treated as series. When a book finishes, the next one in its series plays, or the top of your Up Next list if it has no series. |
| **Up Next** | Listening list you can reorder. |
| **Finished** | Books are marked finished at the end of playback, or by hand, and the state is sent back to Plex/Jellyfin. |
| **Stats** | Today, this week, all time, 30-day average, streak, books finished, a 14-day chart, top books and authors. |
| **Sleep timer** | 5–90 minutes or end of chapter, a 10-second fade-out, and shake to add 10 minutes (it also restarts the timer within a minute of it running out). |
| **Smart rewind** | The resume rewind grows with time away: 2 s after a short pause, your setting after a few minutes, up to 30 s after a day. |
| **Bookmarks** | From the player (with an optional note) or the notification. Listed on the book page and in the player. |
| **Sound** | Speed per book, with a default in Settings. Optional skip silence and voice boost. |
| **Progress sync** | Your position is sent to Plex/Jellyfin (at most once a minute while playing, and on every pause) and pulled back on launch. The newest position wins. |
| **Home and away** | Plex uses your home network when you're on it and the internet when you're out, re-checking whenever the phone changes network, even mid-book. |
| **Downloads** | Download the next book automatically, delete finished books' downloads, and a Downloads page showing storage used. |
| **Offline** | Books that can't play without a connection are dimmed, and their play button says why. |
| **Browse** | Author and series pages. Tap the name on a book's page. |
| **Widget** | Home-screen widget with cover, ±15 s and play/pause. |
| **Stats goal** | Yearly reading goal ring and a shelf of this year's finished books. |
| **Security** | Server tokens are encrypted with an Android Keystore key and kept out of stored URLs. Downloads and cached covers are excluded from backups. |
| **Collections** | Plex and Jellyfin collections (and series folders on the phone) on Home, in the Library and in search. |
| **At a glance** | Top bar with the logo and an animated downloads button; server online status on Home. |
| **Offline changes** | Positions, finished and unread marks that can't reach the server are queued and sent when you're back online (or on the next sync). Settings shows how many are waiting. |
| **Series progress** | Collection cards show "3 of 7 finished" and a bar for how far through the series you are. |
| **Backup** | Settings → Backup saves settings, places in books, finished books, Up Next, bookmarks and stats to a JSON file, and restores it (books are matched by library and server id, or title and author). Sign-ins aren't included. |
| **Download location** | Choose where new downloads go: the phone's storage or an SD card. |
| **Lamplight** | A dim, warm theme for bed, chosen directly or switched on automatically between hours you set. |
| **Updates** | With a read-only GitHub token in Settings → Updates, the app checks this repository's latest release on launch (at most every 6 hours) and offers to install it. |
| **Wear OS** | A watch app shows the book, chapter and time left, with play/pause and ±15 s. It controls the phone; it doesn't play on the watch. |
| **Adaptive** | Phones get a bottom bar. Tablets and foldables get a navigation rail, wider grids, a side-by-side book page, and a two-page player spread with the table of contents on the right page. |

## Gestures

| Where | Gesture | Does |
|---|---|---|
| Player | Swipe down (anywhere when scrolled to the top, or on the handle) | Minimise to the mini player |
| Player cover | Double-tap left / right half | Back / forward 15 s |
| Player cover | Swipe right / left | Previous / next chapter |
| Player cover | Long-press | Add a bookmark |
| Mini player | Swipe up (or tap) | Open the player |
| Resume, Library | Pull down | Sync with your servers |
| Library | Scroll down / up | Hide / show the chips under the search bar |
| Up Next | Swipe a book left | Remove it from the list |
| Up Next, Customise Home | Drag the ≡ grip | Reorder |
| Any book cover or row | Long-press | Quick menu: play, Up Next, mark finished or unread, download |

## Audiobookshelf

Add it in Settings with your server address (e.g. `http://192.168.1.10:13378`), username and password. Audiobookshelf's series become collections (with their book numbers), as do its collections. Progress syncs both ways. Audiobookshelf 2.26 and later issue short-lived access tokens; the app keeps the refresh token (encrypted) and renews them on each sync.

## Wear OS

The watch app is a separate APK (`LeatherboundListening-Wear-X.Y.Z.apk` on each release). It needs the phone app, Google Play services on both devices, and the same signing key as the phone app (the release APKs have it). To install it, turn on ADB debugging and Wi-Fi debugging on the watch (Settings → Developer options), then:

```
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install LeatherboundListening-Wear-X.Y.Z.apk
```

## Update checks

The repository is private, so the app needs a GitHub token to see its releases. On GitHub: Settings → Developer settings → Fine-grained tokens → Generate new token; Repository access: only Leatherbound-Listening; Permissions: Contents → Read-only. Paste it into the app's Settings → Updates. Android asks once to allow the app to install updates, then asks you to confirm each one.

## Building

You need Android Studio (Ladybug or newer) or JDK 17 with the Android SDK (API 35).

1. Open this folder in Android Studio. It downloads Gradle 8.11.1 and generates the wrapper.
2. Run the `app` configuration.

On the first build, the `fetchFonts` task downloads Libre Baskerville from the Google Fonts repository (SIL Open Font License) into `app/src/main/assets/fonts/`. If that download fails, the app uses the system serif font.
Bookerly is Amazon's proprietary font, so it can't be bundled. Libre Baskerville is the closest open alternative.

From the command line (on this machine, the JDK is in `~/.local/share/jdks/jdk-17.0.21+9` and the SDK in `~/Android/Sdk`, and `local.properties` already points at the SDK):

```
export JAVA_HOME=~/.local/share/jdks/jdk-17.0.21+9
./gradlew assembleDebug        # or assembleRelease for the smaller, optimised APK
./gradlew testDebugUnitTest    # unit tests: chapter reader, rewind, positions, progress merging, sorting
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Versions and releases

The version is in `version.properties`. Note changes under **Unreleased** in `CHANGELOG.md` as you go, then run:

```
scripts/release.sh 1.1.0
```

This bumps the version, commits, tags `v1.1.0` and pushes. GitHub Actions then runs the tests, builds the signed APK and publishes it as a GitHub Release with that version's changelog. Every push to `main` also builds and tests (`.github/workflows/ci.yml`).

Releases are signed with the key in `keystore.properties` on this PC, and the same key is stored as GitHub secrets for the release build. It's the Android debug key the app was first installed with, so each release installs as an update. Neither the key nor its password is in the repository.

## Library layout tips

- **Plex**: put audiobooks in a *Music* library, with artist set to the author and album set to the book. Each book's files are its tracks. Collections become series, in the order the collection lists them.
- **Jellyfin**: either a *Books* library (AudioBook items) or a *Music* library works. Files are grouped into books by album. BoxSet collections become series.
- **Local folders**: each folder that contains audio is one book. A layout of `Author/Series/01 Book Title/` gives you the author, the series and the series order. A `cover.jpg` or `folder.jpg` is used if present, otherwise the embedded artwork.

## Android Auto

Debug builds come from an unknown source, so Auto hides them. To allow them, open the Android Auto app, tap the version number 10 times to unlock developer settings, then turn on **Unknown sources**.

## Project map

```
data/            Room entities + DAO, DataStore settings, repository (sync/merge), downloads
data/remote/     Plex, Jellyfin and Audiobookshelf clients (OkHttp + kotlinx.serialization)
data/local/      SAF folder scanner, MP4 chapter reader
playback/        PlaybackService (MediaLibraryService for notification/lock screen/Auto),
                 AudiobookPlayer (cross-file skip, rewind-on-resume), PlayerConnection (UI controller)
ui/              Compose theme, book-style components, screens, shared ViewModel
wear/            The phone side of the watch link (Data Layer)
../wear/         The Wear OS app (separate module)
```
