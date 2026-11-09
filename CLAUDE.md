# AETHER: project rules for coding agents

AETHER is a music app for Android: YouTube Music's catalogue, music quality first, in a black,
editorial, ASCII-and-glass visual world. `docs/product.md` is the design brief and how it's built;
`docs/architecture.md` says where each engine part comes from (PODIUM at D:\PODIUM, LastWave-native)
and why; `docs/ytmusic-verification.md` is what YouTube Music answers, verified live. Update them
when behaviour changes.

## Non-negotiables
- **Quality is measured, never claimed.** "Lossless" and "Hi-Res" only when the decoder measured
  it; "Bit-perfect" only when the path was verified. Unknown means no label, never a guess.
- **Open media only.** Custom sources play unencrypted streams the listener adds. No content-key
  decryption, no licence proxies, no bundled sources (architecture.md §5).
- **No analytics, no tracking.** No telemetry or crash SDKs. No YouTube playback-stats or history
  pings unless the listener opts in while signed in.
- **Providers never leak into screens.** Screens use `core:model` types and the `MusicSource`
  interface; provider code lives in `sources:*`.
- **The service's answers are untrusted input.** Parsers fail soft and never throw
  (`ytmusic/…/Json.kt`); never branch on display text where a page type or id says the same.
- **Black and white, and the music's own colour.** `#000` background, white type, greys. Album and
  song covers are in their own colour on cards, in lists and in Now Playing (`ArtTreatment.Cover`). The
  head of an album's or artist's page, picture and film, is black and white, as is every other picture
  (artists, moods, the masthead: `ArtTreatment.Print`).
  What's made from a cover stays neutral: a card's frost is grey, a playlist folder is grey, and only
  the songs' covers on it have colour. No Material components; glass only through `core:designsystem`
  (`Modifier.liquidGlass`).
- **One glass: the tab bar's.** Never change the tab bar's own glass (`GlassWeight.Control`,
  `TabBar`/`Rooms` in `Shell.kt`). Everything else is made from it: pills and keys are its dragged
  selector's clear lens (`GlassWeight.Key`), sheets the same glass with a deeper frost (`Pane`).
  Glass that sits on a page has nothing to sample; it draws the lens's rim (`drawLensRim`). Glass is for
  what can be touched: a label is plain spaced capitals (`Strapline`), never a capsule.
- **Type by role, as on Listen.** Pinyon Script for names of pages and the wordmark, and nothing else
  (a name all in capitals goes in the serif's capitals: `Type.name`); EB Garamond for section headings
  and for reading; Montserrat capitals for the names of things (tracks, settings, menu entries). No
  other faces (JetBrains Mono only where characters are pictures; the Line lyrics are a drawing, not a face). An artist's own lettering, where
  Apple Music has it, stands in for their name, in white.
- **Motion is springs.** Apple's response and damping (`theme/Fluid.kt`, `Motion.fluid`), never a
  timed tween for anything that moves; interruptions keep their velocity.
- **Design first.** This is for a design company: judge every change by how it looks and feels,
  and check it on the phone (screenshots) before calling it done.
- **Nothing fake.** No hardcoded content, no buttons without an action. A feature a source can't
  support says so.
- **No secrets in code or git.** Sessions are sealed at rest with the Android Keystore.
- **GPL-3.0.** Ported files keep their notices.

## Environment
- Windows 11, 7.7 GB RAM: keep the Gradle heap caps in `gradle.properties`.
- JDK 22 on PATH (targets 17), Android SDK at `D:\Android`, Gradle from PODIUM's cache.
- Test phone over adb: A059, Android 37, 1080×2392 at 420 dpi, portrait only.
- `./gradlew :ytmusic:test` (fixtures), `./gradlew :core:model:test :player:testDebugUnitTest` (the equaliser's sums and
  audio), `./gradlew :ytmusic:liveCheck` (live service),
  `./gradlew :app:installDebug` (the phone).
- Debug builds only: `adb shell am start -n app.aether.debug/app.aether.MainActivity --es aether.preview welcome`
  shows the welcome again without touching the listener's settings.
