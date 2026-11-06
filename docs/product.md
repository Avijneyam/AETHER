# AETHER: product and build plan

Written 2026-11-03 from the master brief, the CANTICUM mark (serif capitals, a cross of light,
MMXXVI) and four reference recordings (Now Playing covers, the glass carousel, the glass folder,
the liquid-lens boot). The engine decisions are in `architecture.md`.

## 1. The world

Black, white, greys. A private listening room: oversized editorial serif headings, centred like a
fashion house's pages, small letterspaced sans for everything functional, monochrome artwork that
is sometimes rebuilt from characters, dots or dither, a little grain, and a few pieces of liquid
glass that answer to touch like a drop of water. Portrait only. No light theme.

## 2. What each reference became

| Reference | In AETHER |
|---|---|
| CANTICUM card | The wordmark: AETHER in spaced serif capitals over a cross of light, "MMXXVI" below |
| Liquid lens logo (recording) | Cold-start boot: a droplet lens ripples open, the mark shows through it in scanlines with rainbow-fringed rims, then the lens melts and the mark resolves |
| Venice's water (venice.ai) | The welcome: a filmed sea at night (Pexels 27700444, free licence), graded black and white, slowed to half speed, 16 s with its end dissolved into its start so it loops with no seam (`res/raw/welcome_water.mp4`, 4.1 MB); the wordmark in the middle over a pool of shadow; three keys (YouTube Music, timed lyrics, offline), each asking only when touched |
| Step player (bencho.dev) | `StepIndicator` under every horizontal row: pages behind white dots, ahead grey, the current one a bar that fills when the row comes to rest and eases on to the next dot as you swipe. It follows the finger; shelves never scroll themselves |
| Grid dots loader | `GridDots`: a small dot grid pulsing in a diagonal wave, everywhere something loads |
| Glass carousel (recording) | Every horizontal list bends light at the screen edges: items entering or leaving are magnified, stretched and colour-split as if through thick glass (`Modifier.edgeRefraction`) |
| Glass folder (recording) | Playlists: the cover fills the folder's back, songs peek out as cards, the front is frosted glass of the cover with the title; it leans toward the finger and its songs rise |
| Now Playing (recording) | A portrait cover dominates, monochrome with grain and scattered uppercase microtype; the next cover wipes up from below overexposed and develops into tone |
| Wake slider | The seek bar: thin bars with no thumb inside a glass pill; a fast drag makes the bars behind the finger spring up in a wake |
| Liquid glass (samasante, BitChord) | `Modifier.liquidGlass`: one glass, the tab bar's (Backdrop refraction, blur, edge light, spring physics on press and drag). Pills and keys are the tab bar's dragged selector: a clear lens with colour fringing at its rim (`GlassWeight.Key`). Sheets are the same glass with a deeper frost |
| Mood and genre tiles | Square-ish tiles, a black and white photograph at the right fading to black at the left, under the tile's name. The catalogue's colours aren't used |
| Artist's head (BitChord) | The source's banner first, a portrait over it, then the artist's short film over that from its first frame, in black and white. The name in the script, until their own lettering, for those who have it, comes up in white |
| Rooms | They ride a rail: the room on your right comes in from the right, and going back reverses it. Pages slide on a stack as on iOS |
| Type | Three faces by role: Pinyon Script for the names of pages, EB Garamond for section headings and reading, Montserrat capitals for names |
| Colour | Only the song that's playing has any: its cover in Now Playing and the mini player, and its row in a list. Everything else is printed in black and white |
| Icons | Phosphor (MIT): Light weight for hairline marks, Fill for play, pause, skip and a liked heart. The tab bar's room marks stay AETHER's |

## 3. Modules

```
:ytmusic           Kotlin     YouTube Music catalogue (built, tested live)
:core:model        Kotlin     Track, Album, Artist, Playlist, Feed…; the MusicSource interface;
                              the playback state machine's types
:sources:youtube   Kotlin     YouTube Music as a MusicSource; its stream resolver
:core:data         Android    Room database (library, playlists, folders, history, queue,
                              searches), repositories, the SourceManager, preferences
:core:designsystem Android    tokens, type, glass and its physics, shaders (ASCII, halftone,
                              dither, grain, edge refraction, lens), components
:player            Android    Media3 service, engine, queue, persistence, notification
:app               Android    navigation, screens, view models
```

Screens see only `core:model`. The `SourceManager` routes each id (`ytm:…`, `local:…`) to its
source, so a new source is a new `sources:*` module and nothing else changes.

## 4. Playback

The service owns the player. Screens subscribe to one `PlaybackState`
(Idle, Loading, Buffering, Playing, Paused, Seeking, Ended, Error) and send commands. The queue is
the player's playlist, persisted on every change and restored on cold start. Streams are resolved
when the player asks for them (`aether://track/<id>`), cached until they expire, re-resolved once on
a 403. Audio leaves at full depth (float output), never resampled by the app.

## 5. Phases

1. Foundation: tokens, type, navigation, models, database, sources.
2. Visual identity: boot, onboarding, loader, artwork treatments, glass, folders, Home, Search.
3. Engine: resolver, service, queue, state, persistence.
4. Player: Now Playing, seek bar, controls, lyrics, mini player, notification.
5. Library: playlists and folders, likes, albums, artists, history, downloads.
6. Interaction: liquid physics, shaders, haptics, transitions.
7. Testing and tuning on the phone.
