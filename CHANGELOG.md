# Changelog

All notable changes to Leatherbound Listening. Add new entries under **Unreleased**;
`scripts/release.sh` moves them under the version number when you release.

## [Unreleased]

## [2.2.1] - 2026-11-01

### Fixed
- Steering-wheel buttons in Android Auto now work: previous and next jump back and forward through the book, as do headphone buttons

### Added
- Settings → Android Auto: how far back and forward previous/next jump (30 s each by default, 5 s to 2 min)

### Changed
- The notification, lock screen and Android Auto now show previous/next (using those jumps) where the ±15 s buttons were, since all three share one set of controls. The player in the app keeps ±15 s

## [2.2.0] - 2026-11-01

### Added
- Update check: add a read-only GitHub token in Settings → Updates and the app checks for a new release when you open it, shows what's new, and installs it
- Changes made offline reach your server later: positions, finished and unread marks that can't be sent are queued and sent when you're back online. Settings shows how many are waiting
- Series progress on collection cards: "3 of 7 finished", with a bar for how far through the series you are
- Audiobookshelf libraries (Settings → Add Audiobookshelf), with series, collections and two-way progress sync
- Backup and restore (Settings → Backup): settings, places in books, finished books, Up Next, bookmarks and listening stats, in one file
- Choose where downloads are saved: the phone's storage or an SD card
- Lamplight: a dim, warm theme for listening in bed, which can switch on automatically at night (9 pm to 7 am by default)
- Wear OS app: what's playing, chapter time left, play/pause and ±15 s on your watch (a separate APK on each release)

### Changed
- The status bar and navigation bar icons now follow the app's theme rather than the phone's

## [2.1.2] - 2026-11-01

### Added
- Plex switches between home and away automatically: when the phone changes network (Wi-Fi to mobile data or back), each server's best address is picked again, so it streams over your home network when you're on it and over the internet when you're not. A book that's playing moves to the new address without losing its place, and a stream that drops is retried once on the server's current address before showing an error

### Changed
- Library: the title bar and search bar now stay put. Below them are the Books/Collections chips, the status chips and the volume count, and only those hide when you scroll down

## [2.1.1] - 2026-10-31

### Changed
- Library: scrolling down tucks away the title, sort and view buttons, and chips, leaving just the search bar above your books. Scrolling up brings them straight back
- Library: the volume count now sits under the "Library" title, and the status filter chips sit above the search bar

## [2.1.0] - 2026-10-31

### Added
- Drag and drop to reorder Up Next (hold the ≡ grip). This replaces the up and down arrows
- Customise Home (the sliders button next to the greeting): drag to reorder shelves and switch them on or off. Reset restores the default order
- "On device" shelf on Home showing your downloaded books
- Genre view: sorting the Library by genre shows a card per genre (like collections), each opening a page of its books. The filter button chooses which genres are shown

### Changed
- "Continue listening" is now "Jump back in", on Home and in Android Auto
- Home shows Up next above Jump back in by default
- Stats tiles are all the same size
- The Library search box just says "Search"
- Automatic downloads always take the next book in the series first. Up Next is only used when the series has nothing left
- A book's series is now the smallest collection it's in, so a broad collection like "Favourites" is no longer mistaken for a series
- Genres that differ only in capitalisation ("Fantasy", "fantasy") are merged

### Fixed
- Custom collection artwork from Plex (and Jellyfin) now shows on collection cards

## [2.0.0] - 2026-10-31

### Added
- Top bar on every page with the app logo (tap for Home) and a downloads button. It animates, shows overall progress and counts queued books while downloads run; tap it for the Downloads page
- Server status on the Home screen: a chip per library showing Online, Offline or On this phone, rechecked every minute and when your connection changes
- Collections: Plex collections, Jellyfin collections and series folders on the phone, on a Home shelf, as a Books / Collections switch in the Library, and on their own page
- Library search also finds collections (shown above the matching books) and genres
- Sort the library by genre, with a heading for each genre
- "Mark as unread" in the long-press menu, for books with progress or history. It clears progress on Plex/Jellyfin too
- Downloads page shows speed, size so far and total size, time left and which file is downloading, with in-progress, failed and downloaded sections

### Changed
- The "Resume" tab is now called "Home"
- Automatic downloads fetch only the next book, and only once you're 80% through the current one
- Deleting or cancelling a download, and "Remove finished", now ask for confirmation first

## [1.0.0] - 2026-10-31

First release.

- Plex (account sign-in or URL + token), Jellyfin and on-device folders, with metadata, covers and chapters cached locally
- Book-style design: parchment and leather themes, Libre Baskerville type, gilt frames, two-page player on tablets
- Playback with chapters, ±15 s skips across files, rewind on resume (smart rewind by time away), speed per book, skip silence and voice boost
- Sleep timer with fade-out, end-of-chapter mode and shake to extend
- Bookmarks with notes, from the player or the notification
- Notification, lock screen, Bluetooth and Android Auto controls, with chapter titles and covers
- Two-way progress sync with Plex and Jellyfin; automatic library sync on open and hourly
- Offline downloads with auto-download of the next book and a downloads manager
- Up Next list, series queueing from collections, author and series pages
- Stats with daily chart, streak, yearly goal and finished shelf
- Home-screen widget and gesture controls
- Server tokens encrypted with the Android Keystore
