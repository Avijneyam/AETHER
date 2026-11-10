# Architecture decisions

Written 2026-11-03, after reading PODIUM (D:\PODIUM) and LastWave-native (Clash-Projects, commit
`78ed262`, 2026-11-03) side by side and checking YouTube Music live (`ytmusic-verification.md`).
The UI is designed separately; nothing here decides how anything looks.

## 1. What the app is

A YouTube Music client for Android that sounds as good as the phone and the listener's equipment
allow, with the whole of YouTube Music's catalogue (Home and its chips, Explore, Charts by country,
Moods & genres, new releases, podcasts, albums, artists, playlists, search, radio, lyrics), the
listener's own music offline, sources the listener adds, and their Spotify playlists, lyrics and
Canvas. No analytics, no tracking.

## 2. Where each part comes from

| Part | From | Why |
|---|---|---|
| Catalogue | **Built** here: `:ytmusic`, PODIUM's client and fail-soft parser, extended to every surface LastWave covers (its home parsing follows Metrolist's) | Every guest surface verified live, 14 fixture tests |
| YouTube stream resolution | **PODIUM**: `YouTubeStreamWalker`, `PlayerIdentities`, `PlayerScripts` (yt-dlp-ejs), `PoTokens`, `StreamHttp`, NewPipe failsafe | Its first app (`VISIONOS`) plays today; it proves both ends of a stream before the player gets it and skips DRC variants |
| Delivering YouTube streams | **LastWave** `ChunkedRangeDataSource` | ~1 MB range requests avoid googlevideo's throttling of open-ended ranges |
| Playback engine and output | **LastWave**: `MusicPlaybackService`, `NativeProcessingAudioSink`, `NativeAudioEngine` and `cpp/` (C++17 DSP on Oboe, libsoxr), `UsbBitPerfectOutput`, `ExclusiveUsbOutput` with `audio/decent-usb-audio-driver`, `UacFeatureVolume`, `UsbDacMonitor`, `DirectTrackStream`, `InternalDirectStream`/`DirectOutputStream`, `SignalPath`, `QualityBadge`, `LoudnessNormalizer`, parametric EQ and AutoEQ, crossfade (`CrossfadeSchedule`) and AutoMix, Android Auto, Cast | Far ahead on every quality measure (§3) |
| Quality honesty | **Both**: PODIUM's rule (the measured format wins over the reported one; "Lossless" and "Hi-Res" only when measured) with LastWave's `QualityBadge` and `SignalPath`, which already refuse to guess a bit depth or call a mixed path bit-perfect | Never claim what isn't measured |
| ALAC | **PODIUM** `AlacAudioRenderer` (bit-exact port of Apple's decoder) | Phones without a working ALAC decoder |
| Offline library | **PODIUM**: MediaStore scan, Room database, offline playability, local full-text search, downloads, stream cache keyed by song and quality | LastWave only lists picked folders as one playlist |
| Custom sources | **Both**: the addon protocol they share (`manifest.json`, `/search`, `/stream/{id}`); LastWave's quality metadata (bit depth, sample rate, hi-res and Atmos flags, not stopping at a downgraded answer) and `StreamAlignment`; PODIUM's exact-recording matcher, per-source health and circuit breaker, 5 s budget, background retries and mid-song crossfade to the better copy | §4 |
| Spotify | **LastWave**: sign-in on Spotify's own page (`sp_dc`), web-player tokens, playlist import matched to YouTube Music, synced lyrics with syllable timing, Canvas videos | Data from the listener's own account; it never plays Spotify audio |

## 3. Audio quality: PODIUM against LastWave

| | PODIUM (as built) | LastWave | Taken |
|---|---|---|---|
| YouTube Music audio | Opus ~160 kbps; 256 kbps with Premium | The same: both rank by bitrate | Same ceiling; PODIUM's resolver |
| 24-bit files | Played as 16-bit: float output is never enabled (`setEnableAudioFloatOutput` isn't called, though `audio-architecture.md` §6 says it is) | Float32, or the decoded integer PCM untouched | LastWave |
| Sample rate | Android's mixer resamples (usually to 48 kHz) | libsoxr high-quality resampling, or none on a bit-perfect path | LastWave |
| USB DAC | Through Android's mixer (bit-perfect is documented, not built) | Android 14 bit-perfect USB mode for the exact format sent, or its own USB Audio Class driver straight to the DAC (Android's mixer never sees the audio) with the DAC's own volume | LastWave |
| Phone's own DAC | Android's mixer | "HiRes Direct" (24-bit at the source rate, which some Qualcomm phones route to an unmixed direct output; tested on each phone before use) and exclusive MMAP AAudio | LastWave |
| Processing | 10-band graphic EQ, sound effects | Native parametric EQ, AutoEQ headphone correction, loudness normalisation (track or album), all bypassed on bit-perfect paths | LastWave (PODIUM's effects can stay as extras) |
| Crossfade, gapless | Both; gapless through Media3 | Both, plus AutoMix | LastWave |
| Lossless streaming | The listener's own sources, open media only | Tidal, through addons (§5) | Neither as it is: §4–5 |

PODIUM's column is from reading its code (neither `setEnableAudioFloatOutput` nor
`setPreferredMixerAttributes` appears anywhere in it), not from measuring a phone. The listening
difference is in the output chain, and there LastWave is clearly better. On YouTube Music itself
the two get exactly the same audio.

## 4. Custom sources

A custom source is an addon the listener adds by address. For a song from YouTube Music, it's asked
for an exact copy of that recording (lossless when it has one). The song still comes from YouTube
Music: the catalogue, autoplay, likes and history stay there, and YouTube Music plays it whenever no
source has an exact copy or the sources are slow. When a copy turns up mid-song, playback moves to it
with a short crossfade, aligned to the same position.

**They play open media only.** A stream that's encrypted, or that needs a licence, is refused (this
is PODIUM's rule D-91, kept). The app ships no sources.

## 5. Not taken from LastWave, and why

LastWave's lossless and hi-res audio is Tidal's, reached through addon servers it signs requests
to with a secret compiled into its native code:

- **hybrid-v1 addons** (`HybridCenc`, `HybridMediaServer`): the addon hands over a content key, and
  the app downloads Tidal's encrypted FLAC from Tidal's CDN and decrypts it on the phone.
- **Modules** (`ModuleDrmFactory`, `ModuleOfflineLicense`, `ModuleCrypto`, `SegmentedDash*`,
  `SegmentedMpdBuilder`): Widevine licence requests sent through third-party servers with their
  headers.
- The Tidal search and stream flow in `LosslessMusicApi`, `NativeSecrets`/`SecretsBridge.cpp`, and
  the download path that decrypts and remuxes those streams.

That is getting past DRM and serving subscription music to people without the subscription, so
none of it is ported. Lossless comes from where the listener has the right to it:

- **Their own files**, offline: FLAC, ALAC and WAV up to 32-bit/384 kHz, bit-perfect to a USB DAC.
- **Their own servers or open sources**, as custom sources (a Navidrome, Jellyfin or Subsonic
  server behind an addon).
- **YouTube Music Premium** on their own account: 256 kbps.
- A service's own route with the listener's own subscription, if one allows a third-party app
  (TIDAL publishes an Android SDK; whether its terms allow full-track playback here is unchecked).

## 6. Privacy

- No analytics or crash-reporting SDKs (neither project has any). Diagnostics stay on the phone.
- No YouTube playback statistics or history pings. Sending plays to the listener's YouTube history
  is an explicit opt-in, and only when signed in.
- LastWave's Last.fm scrobbling and Discord presence aren't taken unless asked for.
- Artist and album art ask Apple only with the listener's setting on (on by default, Settings, "Artist
  and album art"), sending the artist's name and the album's title and nothing else, under AETHER's own client name. Its answers are kept on
  the phone. Timed lyrics are the same, with the lyrics libraries (LRCLIB, lrc.red, BiniLyrics,
  BetterLyrics, LyricsPlus, Unison, KuGou), each sent a song's title, artist, album and length and
  nothing else, each one switchable in Settings. Romanisation is worked out on the phone; nothing is sent.
- AETHER's own updates: when it opens (release builds; Settings, "Look for updates"), GitHub's API is
  asked for the newest release of `GHOSTxASR/AETHER`, at most every six hours, with nothing about the
  listener. A release is installed only when the listener asks, after its SHA-256 matches GitHub's and
  Android confirms it's this app, newer, signed with the same key.

## 7. Licensing

PODIUM and LastWave are GPL-3.0, so this app is GPL-3.0. Ported files keep their notices (LastWave's
home models credit Metrolist). Third-party parts keep their own licences and notices:
decent-usb-audio-driver (MIT), libsoxr (LGPL-2.1, built as a shared library), NewPipeExtractor
(GPL-3.0), and the rest as PODIUM's and LastWave's `third_party` notices list them.

## 8. Modules (planned)

```
:ytmusic           pure Kotlin   YouTube Music catalogue                         built
:ytmusic-streams   pure Kotlin   PODIUM's stream walk, NewPipe failsafe          next
:player            Android+NDK   LastWave's engine, PODIUM's ALAC                next
:library           Android       PODIUM's offline library and database          next
:sources           Kotlin        custom sources (§4)
:spotify           Android       LastWave's Spotify integration
:app               Android       the UI, once designed
```

## 9. Built in the November polish (2026-11-04)

- **Glass**: Kyant's Backdrop v2.0.0 vendored from BitChord (GPL-3.0) with its backdrop resolution
  scale, in `core/designsystem/.../glass/backdrop` (support for Kyant shapes restored). Liquid uses
  BitChord's values (frost 8 dp, lens 24 by 24 with colour fringing, a third of the resolution);
  Simple frosts small floating controls at a quarter resolution and draws panes solid with a lit rim.
  Stretch never carries an edge more than 6 dp past where it rests. The tab bar's selection becomes a
  lens over its own glass while held (BitChord's `GlassSelectionPill` idea).
- **Motion**: springs named by response and damping (Apple's WWDC18 model, on Compose's own exact
  spring), momentum projection for where a flick lands, Apple's rubber band past edges.
- **Lyrics**: PODIUM's lyrics layer ported as `:core:lyrics` (LRCLIB, LRC and word timing, matcher,
  file cache). On by default (LRCLIB is sent a song's title, artist and length, nothing else); the
  listener turns it off on the welcome or in Settings, never from the lyrics view. Off, or when
  LRCLIB has nothing, the music service's own words, paced across the song and marked as estimated.
- **Offline**: Media3 downloads into a kept store keyed by song id (`track:<id>`), never by stream
  address; the player reads it first and never writes to it while streaming. Each download carries
  its track as JSON. `KeepService` runs in the background with progress.
- **The phone's own music**: `:sources:local` reads MediaStore (with the listener's permission) and
  plays files straight from the phone.
- **Sign-in**: YouTube Music on Google's own page in a locked-down web view; the session cookies are
  sealed with a Keystore key (`core/data/Account.kt`).
- **Bit-perfect**: Android 14's preferred mixer attributes, asked only for formats known exactly
  (streamed songs decode to 16-bit at their rate); reported only when the phone confirms the mixer
  took it and media is playing to that DAC. LastWave's own USB driver and native DSP are not ported.
- **Custom sources**: `:sources:addon`, PODIUM's lenient protocol reader (`manifest.json`, `/search`,
  `/stream/{id}`); encrypted streams refused. Exact-copy matching is built (2026-11-10, below);
  mid-song switching to a copy that answers late is not.
- **Pictures**: every picture is kept in Coil's memory cache under its own key (address, size,
  decoding) and looked up while composing (`art/Pictures.kt`): anything seen before draws on the
  first frame (folders flying to their pages never reload); anything new fades in on a spring.
- **Drags** (`DragState`): the finger is held in plain state, so no late step can cancel a release;
  a flick closes a pane however short the pull; lists hand their overscroll to the pane. A screen
  covered by Now Playing, or Now Playing covered by the lyrics, isn't drawn at all.
- **Pages** (`ui/Navigation.kt`, `ui/Pages.kt`): every room stays made, and every page on a stack stays
  made beneath the one on top; Settings is made once and kept. The rooms ride a rail (`RoomRail`): the
  screen moves along it, so the room on your right comes in from the right and this one goes out to
  the left, and a room you jump to is brought up beside what's showing rather than flown past those
  between. Pages slide on a stack as on iOS. Moving one is a property of its layer, with nothing
  composed, measured or recorded again; what can't be seen stays made but isn't placed, so its
  textures are freed. Motion runs on `Glide` (`theme/Fluid.kt`): a spring that advances by at most a
  thirtieth of a second per frame, so that a long frame holds it instead of skipping it, which is what
  lost a folder's flight the first few times. Measured: rooms and rapid switching about 6% janky frames.
  Entering Discover had one frame of 50 to 350 ms of GPU work during the slide. Bisected on the phone,
  it was the recent searches: page glass strokes and clips Apple's continuous outline (an arbitrary
  path) six times a capsule, all first drawn together. Small page glass is now painted to images while
  its room is measured out of sight (`PaintedGlass` in `LiquidGlass.kt`); the frame is about 40 ms.
- **What's costly to draw** is kept as a small texture (`Modifier.retained`), best with no layer inside
  it: a folder's two flat paintings (tilted afterwards, as painting its outlines under a 3D tilt took a
  tenth of a second or more), the ribbon's capsules, a still print's shader. Sections and pages only
  get a layer of their own without a texture (`Modifier.separate`): textures for those held graphics
  memory at Android's limit (372 MB on this phone, 280 MB of it render targets) with only 40 MB free.
- **The cost of glass**: glass samples the page by reference, so Android visits the page twice a frame
  and treats everything over it as changed. Any frame at all redraws all the glass. GPU milliseconds
  in `gfxinfo` don't show this: the phone lowers the GPU's clock to match the load, so they stay
  about level; use janky frames, and `framestats` for each stage. Fewer frames aren't the answer
  either: stepping the ribbon on every other frame dropped the clock until frames missed their deadline.
- **Mood ribbon, drifting covers**: they slide as one picture by fractions of a pixel, and the list
  moves only every so often, since a list stands on whole pixels. They use `dispatchRawDelta`: a
  `scrollBy` loses to a finger with a cancellation, which ended the ribbon's loop for good.
- **Artist art** (`:sources:apple`, after BitChord's `AppleArtistArt`): the iTunes search API finds the
  Apple artist by name, believed only when their catalogue shares a title with the page; their public
  page gives a portrait, for some their name as a lettered picture, for some a looping film. The
  film is video only and unencrypted (checked: no `EXT-X-KEY`), so it's within §5. This isn't an API
  Apple publishes: any surprise is "no art", and the source's picture is always there first. It's
  Apple's artwork, fetched at run time and shown as the listener's own client would, and not bundled.
- **Album art** (same module): the iTunes search API finds an album of the same title by an artist of
  the same name, and its public page's header gives, for some albums, the cover as a square looping
  film (`videoArtwork.motionDetailSquare`; video only, no `EXT-X-KEY`, checked). Seen live on 2 albums
  of 6 tried. Apple has no lettering for albums; the album proves who the artist is, so it brings the
  artist's. Both films are shown in black and white.
- **On this phone** (`:sources:local`, `core:model` `Folders.kt`, from PODIUM's `LocalMusicSource`, `FolderSelection`
  and folder tree): the media store's songs with their folders, watched so changes show by themselves; the
  listener's choice of folders (a folder follows the closest choice at or above it; app audio is out to begin
  with) lives with the source. Kept songs are Media3 downloads. Each song carries the store's album id,
  album artist (Android 11 on), disc (the thousands of its number) and when it came onto the phone.
- **Albums offline** (`ui/library/OfflineAlbums.kt`, as PODIUM's `albumsOf`): on Listen, the phone's
  songs as albums (by the store's album, its album artist or the one artist all its songs share, else
  "Various artists", which names no one to look up; disc by disc, track by track; the albums that came
  onto the phone last first), and the kept songs as albums (by the catalogue album they're from; a song
  from none stands alone; the last kept first). An album opens on the album page's head (`AlbumHero`)
  with play, shuffle and add to queue; keeping and saving don't apply to songs already on the phone.
  The shelf's "more" opens the whole of it, folders and all.
- **Kept playlists and albums** (`KeepSync`): keeping one remembers what it held; while the app runs, every six
  hours (at once for the listener's own), on unmetered networks only, what's new is kept. Only additions: a song
  taken off the phone isn't brought back, and nothing is deleted. Remembered in preferences, not the database.
- **Profile** (`ui/profile`): computed on the phone from the listening history (`ProfileStats`, pure), shown from the
  first song. Time is the lengths of songs played past their first half-minute, not a stopwatch. Genres are iTunes'
  `primaryGenreName` for the most played artists, under the artist-art setting, and left out when unknown. Kinds of
  song come from titles. The card is drawn once at a fixed density, so it's always 1080 by 1920, and captured from a
  graphics layer; shared through a `FileProvider` copy, saved through the media store.
- **The statue morph** (`core/designsystem/.../art/DotMorph.kt`): a programmable mesh (Android 14) of one quad per visible
  dot. Dots are ranked by brightness in 24 equal shares, and within a share along a Hilbert curve, then paired by rank
  between the two prints. Built off the main thread before each change and dropped after; each frame is one uniform and
  one draw call. The dots use the halftone's lattice and tone, so the prints at either end hand over without a jump.
- **Equaliser** (`player/.../Equaliser.kt`, `core/model/.../Equaliser.kt`, `core/data/.../EqualiserStore.kt`): PODIUM's
  `EqualizerProcessor` extended from ten fixed bands to any peaking, shelving, low-pass or high-pass filters, and crossfaded
  so that a change never clicks. It lowers everything by the curve's highest point, so a boost can't clip, and with no
  tuning it passes samples through unchanged. The headphones are AutoEq's parametric results (MIT), measured by
  oratory1990, HypetheSonics, Rtings, kr0mka and Kuulokenurkka, in `core/data/src/main/assets/eq_headphones.json`.
  Imported files are read by `EqText`, which never throws and keeps every number within range.
- **Song video** (`SongFilm`, `AppleArt.findSongFilm`): the iTunes search finds the song by title and artist, and its
  album's public page gives the tall or square film (no `EXT-X-KEY`, checked). BitChord and LastWave also ask Tidal,
  Spotify and a community index; those need a borrowed client token, the listener's Spotify cookie or an unknown
  third party, so they aren't taken. LastWave's music videos for songs (another upload, matched and aligned to the song's audio) aren't taken either; a track that is itself a video plays its own pictures (Music video, below). A link
  under 3 Mbps doesn't ask, and a film with no frame after 9 s is let go.
- **Line lyrics** (`app/.../ui/lyrics/line`, from PODIUM's `feature/nowplaying/line`): the drawing is
  prepared off the main thread for each song, and each frame only maps the position to a cursor. In
  AETHER's white on black, not PODIUM's ivory and gold. Words in scripts the pen doesn't write are traced
  whole from the system font (shaped, so Urdu and Hindi come out right), and Urdu runs from the right. Its swell with the bass isn't brought over: that
  needs the meter (`AudioMeter`, now in the path for lining up copies) to measure all the time, and it
  measures only while a copy is being lined up: nothing is measured for the sake of a drawing.
- **Exact copies from the listener's sources** (built 2026-11-10; `SourceManager`, `sources:addon`
  `ExactCopy`, `player` `ChosenSources`): for a song from YouTube Music, every addon the listener added
  is searched (the song's name with its version and first artist, then its name alone) and asked for a
  copy that is PODIUM's EXACT tier: the same title identity and version markers both ways, the same
  edition (explicit and clean never cross), a shared artist and no stranger featured, both lengths known
  and within 2.5 s, a remaster on one side only from the same album, never an Atmos row for the stereo
  song. All addons together get 5 s; the first copy plays, chosen once per play so every read of it is
  the same file; YouTube Music plays whatever they don't have. A miss is remembered half an hour, a
  refused copy ten minutes; an addon that fails twice rests (30 s, doubling to 15 min), and one that
  asks for a pause gets it. Every request carries the addon's declared settings at the listener's
  values or their defaults, and `quality` at the best option the addon lists (`LOSSLESS` when it lists
  none), as BitChord's host sends it: asked for nothing, addons answered at their own default, which is
  why a BitChord addon "didn't change the quality" before. BitChord's fields are read (`streamURL` on a
  row, `manifest`/`mediaType`/`format` for DASH and HLS, `encrypted` as a flag or a scheme's name,
  `error`). DASH and HLS copies play (Media3's DASH and HLS modules), after their manifest is checked to
  name no `ContentProtection` or key. The player decides each song's way of playing only as it reaches
  it (`ChosenSources`, lazy preparation): a kept file, the kept store, or the stream as resolved. An
  addon that declares `bitchordAuth` (answering only BitChord's own app, proved by a key Android vouches
  for) isn't added: AETHER can't make that proof and doesn't fake it. Keeping is always the song's own
  source. Now Playing names the source a copy came from beside what the decoder measured.
- **Lyrics libraries** (`core:lyrics` `Libraries.kt`, `LyricsChain`, after BitChord's providers):
  lrc.red, BiniLyrics and BetterLyrics (Apple Music's TTML, syllable by syllable), LyricsPlus and Unison,
  then LRCLIB and KuGou (by the line). All are asked at once; word timing beats line timing beats plain
  words, and among alike answers the earlier library wins; it stops as soon as no library still out could
  do better, and after 7 s takes what it has. Words timed past the song's end by more than 8 s are
  another edit's. Measured 2026-11-10 (`./gradlew :core:lyrics:liveCheck`): lrc.red, BiniLyrics,
  BetterLyrics and LyricsPlus answer word by word for current releases; LRCLIB and KuGou have the Korean
  and Chinese songs the others don't; BetterLyrics refuses a request carrying an album (401), so it's
  asked without one. Not taken: PaxSenix and Musixmatch (they borrow another app's token), Genius
  (scraped pages). The model gained word ends, syllables (character ranges with their own times), the
  answering vocal as a line of its own, and a duet's second voice (`opposite`); the cache keeps them, and
  who the words came from. TTML is read with a hardened DOM parser (no doctype, no external entities).
- **Romanisation** (`app` `Romaniser`, `core:lyrics` `Hangul`): on the phone. Korean in the Revised
  Romanization as it's said (liaison, the nasals, ㄹ beside ㄴ and ㄹ, ㅎ's aspiration, palatalised ㄷ
  and ㅌ before 이); Chinese in pinyin with tones through Android's ICU (`Han-Latin`), a syllable to a
  character; Japanese kana in Hepburn (`Hiragana-Latin; Katakana-Latin`); everything else through
  ICU's `Any-Latin`. A Japanese line with kanji isn't romanised: reading kanji needs a dictionary (a
  word's reading decides each character's), and Chinese readings would be wrong. Chinese and Japanese,
  written without spaces, are a phrase to a word in the model and a character to a star on screen
  (`WordTiming.units`).
- **Verse** (`ui/lyrics/Verse.kt`, `VerseModel.kt`; BitChord's `PlayerLyrics` and `LyricLine`, GPL-3.0,
  ported 2026-11-10 with only the face changed): each line is three copies of one text, laid out alike:
  a dim one (0.45 until the page leaves the line, then whole), a bright one cut to what's been sung with
  its edge feathered 30 dp ahead of the singing by a `DstIn` gradient kept to the row's own band (so no
  row is drawn twice), and, on the line being sung, a 6 dp blurred copy at 0.62 cut to the letters of a
  held word, its bloom letter by letter. Words lift up to 2 dp as they're sung (a held note higher, a
  quick one barely) and settle over 700 ms; a word held long enough (by its length: 1.1 s alone, 0.9 s
  and 200 ms a letter when long) swells a letter at a time, never in Han, kana, Hangul or joined
  scripts. Every other line by its distance: alpha 1, 0.74, 0.6, 0.48, 0.4, 0.34 and blur 0, 1, 1,
  1.7, 2.4 dp, the page at 0.98 and the line being sung at 1. Only a line moving reads the clock. The
  page leads the singing by the silence before the next line (350 to 500 ms) and moves on a critically
  damped spring as long as that lead, where BitChord runs its 400 ms curve; a handover still travelling
  when the next comes is added to, not restarted, so the page keeps its speed, and the three rows after
  the line it's going to are held back 6 % of the lead each by the same spring, so nothing slides
  against the list. Breaks: a bare stamp kept where it lasts 4 s, one marked after any line that says
  when it ends and is followed by 4 s of silence, and before a 4 s intro; three points fill across it.
  The answering vocal (brackets dropped, syllables kept) opens 250 ms before its first word and folds
  after its last; Latin letters light as far through as their line has got. EB Garamond at 800, 34 sp
  on 41 sp. A turn by hand shows every line at 0.8 and finds the singing 0.6 s after it stops if the
  line is in sight, 5 s if not.
- **Music video** (`ui/player/MusicVideo.kt`, `YouTubeStreamResolver.video`): for a track that is a
  video (OMV or UGC), the video key plays its own pictures: the tallest adaptive video format the app
  serves at or under 720p (480p on the data saver; the place it plays in is a phone's width, where 1080
  lines can't be told from 720), H.264 first then VP9, 30 fps where there's a choice, checked at both
  ends like audio. A second, silent ExoPlayer plays only the pictures and follows the engine's clock,
  LastWave's way, every 50 ms and only while it's ready: within 60 ms left alone, 60 to 200 ms nudged
  5 %, 200 to 600 ms 15 %, past that a jump, at most one each 1.2 s (so a jump still loading is never
  jumped again), aimed ahead by what the last one took to land. The player stays with the video's slot,
  as LastWave's stays with its view model: the key turning it off pauses it, and on again it carries on
  from what it had (a new surface, so the cover stays until its first picture); off for 30 s, it's let
  go. The engine's audio path is untouched. Songs keep the cover and its film.
- **Kept songs as files** (`player` `KeptFiles`, `OggOpus`; after BitChord's `DownloadStore` and
  LastWave's `WebmOpusRemuxer`): once a song is whole in the keep (Media3's download), it's written to
  `Music/AETHER/Artist - Title.opus` through the media store (`IS_PENDING` until whole; no permission
  needed for AETHER's own files), YouTube's Opus moved from WebM into Ogg packet for packet (granules
  with pre-skip, the declared end, CRCs; a tag packet larger than a page continues over pages), its tags
  the title, artist, album, year, number, cover (`METADATA_BLOCK_PICTURE`), words as LRC (enhanced when
  timed) and the track itself (`AETHER_TRACK`); AAC goes out as the M4A it is. Then the keep's copy is
  let go, so a song takes its room once. A song that can't be written out stays in the keep and plays
  from there. Kept files play from the file (`keptFile`), are hidden from On this phone, and a file the
  listener deletes in the file manager is no longer kept. Songs kept by 1.0 are written out the same way.
- **Lyrics on the phone** (`LocalLyrics`, `core:lyrics` `EmbeddedLyrics`): a song on the phone brings its
  own words first: an `.lrc` of the same name beside it, in a folder the listener lets AETHER read
  (Android's document tree; Android shows no app another's text files otherwise), then the words in the
  file's own tags (FLAC and Ogg comments, ID3's SYLT and USLT, M4A's `©lyr`), read from its first 6 MB.
  A kept song offline reads the words it was kept with.
- **Updates** (`app/update`, PODIUM's D-99): see §6. The sheet is a `DragSheet` of the Pane glass;
  install through a `PackageInstaller` session, its result through `UpdateInstallReceiver`. Test builds
  look only when asked (`--es aether.preview update` checks as if this were 0.0.1).
- **Listen's figures at rest**: the morph waits for 9 s of being seen and 3.5 s of stillness
  (`LocalPageSettled`: nothing over the page, the rooms' rail still, no page of the room sliding; the
  list not scrolling; no finger down; the app resumed), builds its mesh off the main thread, and runs on
  a clock of its own that advances only while still: a touch mid-change holds it with no frames asked.
- **Full screen** (`MainActivity.applyFullScreen`, PODIUM's D-85): `WindowInsetsControllerCompat` hides the
  system bars with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, again whenever the window has its focus back
  (some phones bring them back after a dialog or the shade). Every page reads the bars' room ignoring
  their visibility (`statusBarsIgnoringVisibility` and its kin), so hidden bars move nothing.
- **Crossfade** (`player` `Crossfader`, `CrossfadeRules`; PODIUM's D-77, built 2026-11-10): Settings ▸
  Sound offers 2, 4, 6, 8 or 12 s, off by default. The queue, session and notification stay with the one
  player; a second, silent player (the same sound path: float output, its own equaliser kept in step,
  `ChosenSources`; no audio focus) starts the outgoing song 4 s before the overlap and is brought level
  with it (within 25 ms) by playing up to 15 % faster or slower while silent; at the overlap the same
  audio swaps to it unheard, the first player moves on to the next song, and the two fade on
  equal-power curves (cos and sin), the new song's fade starting when it really starts. Never between an
  album's songs that run into each other (the same album, the next number: by its id, or on the phone by
  its name and maker), into the same song again, on a song under three times the overlap, or while a DAC
  plays bit-perfect (the mixer takes one stream untouched). A pause, skip, seek, call or error ends it at
  once, at full volume.
- **A better copy, mid-song** (`Crossfader.carry`, `SoundAlignment`, `AudioMeter`, `SourceManager`
  `lateCopies`; PODIUM's D-92 and D-96): the search for an exact copy goes on past its 5 s budget, and a
  copy that turns up is kept apart from the song (still reading its own file, whose every read must be
  the same file) and offered to the player. Playing, the song is carried over: the second player loads
  the copy 6 s ahead and waits there, joins as the song reaches it (or jumps inside what it has loaded,
  or tries again from further ahead), is lined up by its sound (both players' meters record the
  loudness of every 10 ms they decode, stamped with its place in the song; two seconds of onsets are
  compared at offsets up to 500 ms either way, and only a clear match counts, else equal positions) and
  brought level, and a 400 ms equal-power crossfade hands the sound over. The first player plays on
  silent, following the copy, so the queue, position, session and lyrics stay put; Now Playing shows the
  copy's measured format and its source. At the next pause or seek the first player goes over to the copy
  for good (`replaceMediaItem`, chosen afresh, unheard); a song that ends while carried plays its copy
  from its next play. Paused when the copy arrives, it goes over at once; bit-perfect, a crossfade under
  way or under 15 s left, it waits for the next pause or seek. A copy that won't play isn't offered again
  for 10 minutes. The meters are pass-through steps after the equaliser (every byte goes out as it came)
  and measure only while a copy is being lined up.
- **When a song fails** (`PlaybackService.onPlayerError`, `YouTubeStreamResolver.forget`; after PODIUM's
  `handleError`, which refreshes a refused stream once): any failure, not only a 403 or 410, is
  recovered from where the song stopped, twice at most in a row (a song that plays a minute starts
  over): a copy from one of the listener's sources gives way to the song's own stream, and the song's
  own stream is resolved again. The app and format that failed aren't asked for that song for 10
  minutes, so the walk finds another. Measured 2026-11-10 from India
  (`./gradlew :sources:youtube:streamCheck -Pregion=IN -Pdiagnose=true`, or `-Pdiagnose=formats` for
  every format's two ends): `VISIONOS` served every song tried, age-restricted videos too; `ANDROID_VR`
  served the songs (ATV) and refused most videos; `IOS` refused all. BitChord and LastWave keep
  NewPipe's extractor as a last failsafe, as PODIUM does; AETHER doesn't yet.
- **Not yet**: Spotify import (planned through Spotify's own Web API with the listener's own app
  client id and PKCE, not `sp_dc` tokens), Premium-quality streams for signed-in listeners, and
  LastWave's chunked range requests.
