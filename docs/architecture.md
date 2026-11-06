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
- Artist art asks Apple only with the listener's setting on (on by default, Settings, "Artist art"),
  sending the artist's name and nothing else, under AETHER's own client name. Its answers are kept on
  the phone. Timed lyrics are the same, with LRCLIB.

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
  `/stream/{id}`); encrypted streams refused. Exact-copy matching and mid-song switching (§4) are not
  built yet.
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
  **Not solved**: entering Discover has one frame of 50 to 350 ms of GPU work about 0.25 s into the
  slide. The carousel makes it worse without being the whole cause; leaving Discover is clean.
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
- **Not yet**: Spotify import (planned through Spotify's own Web API with the listener's own app
  client id and PKCE, not `sp_dc` tokens), Premium-quality streams for signed-in listeners, and
  LastWave's chunked range requests.
