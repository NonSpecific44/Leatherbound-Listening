# Changelog

All notable changes to Leatherbound Listening. Add new entries under **Unreleased**;
`scripts/release.sh` moves them under the version number when you release.

## [Unreleased]

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
