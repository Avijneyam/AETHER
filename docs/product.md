# AETHER: product and build plan

Written 2026-11-03 from the master brief, the CANTICUM mark (serif capitals, a cross of light,
MMXXVI) and four reference recordings (Now Playing covers, the glass carousel, the glass folder,
the liquid-lens boot). The engine decisions are in `architecture.md`.

## 1. The world

Black, white, greys. A private listening room: oversized editorial serif headings, centred like a
fashion house's pages, small letterspaced sans for everything functional, covers in their own colour
against the black, every other picture in black and white, a little grain, and a few pieces of liquid
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
| Glass folder (recording) | Playlists: a grey glass folder, its songs peeking out as cards in their own colour, the front frosted with the title; it leans toward the finger and its songs rise |
| Now Playing (recording) | A portrait cover dominates, in its own colour (touch it for mono with grain, halftone, ASCII or dither) with scattered uppercase microtype; the next cover wipes up from below overexposed and develops into tone |
| Wake slider | The seek bar: thin bars with no thumb inside a glass pill; a fast drag makes the bars behind the finger spring up in a wake |
| Liquid glass (samasante, BitChord) | `Modifier.liquidGlass`: one glass, the tab bar's (Backdrop refraction, blur, edge light, spring physics on press and drag). Pills and keys are the tab bar's dragged selector: a clear lens with colour fringing at its rim (`GlassWeight.Key`). Sheets are the same glass with a deeper frost |
| Mood and genre tiles | Square-ish tiles, a black and white photograph at the right fading to black at the left, under the tile's name. The catalogue's colours aren't used |
| Artist's head (BitChord) | The source's banner first, a portrait over it, then the artist's short film over that from its first frame, in black and white. The name in the script, until their own lettering, for those who have it, comes up in white |
| Rooms | They ride a rail: the room on your right comes in from the right, and going back reverses it. Pages slide on a stack as on iOS |
| Type | Three faces by role: Pinyon Script for the names of pages, EB Garamond for section headings and reading, Montserrat capitals for names |
| Lyrics | Two ways, chosen in Settings: as constellations (each word a star in a night sky, the default), or PODIUM's continuous line (the song written by hand as one unbroken white line on black, with line art between phrases) |
| Keeping a song | The cover lifts off Now Playing with a small ripple and is carried to the foot of the screen, half inside the phone, a thin ring showing the real download. When it's really there it goes in: the screen is drawn down after it and a wave runs up through everything (AirDrop's ripple), then "Kept on this phone". A failure says so |
| Kept playlists | The keep key on a playlist or album keeps its songs and, from then on, what's added to it later, on Wi-Fi; touched again, it stops |
| Profile | The fourth room. A stack of the most played artists to throw aside; count-up numbers with tiny charts in a hairline grid (after Stats Band); ring gauges with orbiting words that flood white when held (after Orbit Ring Stats); a day as a dial; genres; kinds of song; questions answered by songs. All worked out on the phone. Export makes a glitter-metal card of it at 1080 by 1920, saved to Pictures/AETHER and handed to Android's share sheet |
| On this phone | On Listen, under the ribbon, whatever the network does: offline music as a playlist, then Kept, then the phone's folders, each opening on the folders inside it. Settings chooses which folders are read: a switch on a folder of songs, a square on a folder of folders |
| Album's head | As an artist's: the cover the full width of the screen in black and white, falling away to black under the album's name; the cover's film, also in black and white, over it from its first frame for the albums that have one; the maker's own lettering in place of their name, where they have one |
| Colour | Album and song covers are in their own colour on cards, in lists and in Now Playing. The heads of album and artist pages (picture and film), artists, moods and the masthead are black and white. A card's frost and a playlist folder are grey whatever the cover; only the songs' covers on the folder have colour |
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
