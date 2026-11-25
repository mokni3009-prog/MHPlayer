# MH Player — Design Tokens (extracted from the 9 approved mockups)

> Source: the 9 approved PNG mockups in `~/workspace/iptv-player-app/`
> (`login-approved.png`, `…-loading-v1-…`, `…-home-v3-narrow-sidebar-…`,
> `…-channels-v3-red-selected-…`, `…-movies-v1-…`, `…-series-v1-…`,
> `…-myplaylist-v1-…`, `…-player-fullscreen-v1-…`, `…-details-v1-…`).
> All mockups are 2048×1152 px. Every dimension below is scaled to the
> **1280×720 baseline** (divide mockup px by 1.6) and expressed in **dp**
> (at 720p, 1 dp ≈ 1 px). Font sizes are given in **sp** at the same baseline.
> Colours marked **measured** were sampled from the PNG pixels (median/mode of
> the solid area); colours marked **estimated** are eyeballed from the render.
> The UI language is **French** everywhere. Font family throughout is a
> geometric/system sans (Inter-like): ExtraBold/Bold for titles, Regular for
> body, SemiBold/Bold for buttons and chips.

---

## 1. Global tokens (apply to every screen)

### 1.1 Colours

| Token | Hex | Where used | Source |
|---|---|---|---|
| `color_background` | `#0A0A0C` | App background on all screens | measured (bg `#080808`–`#0A0A0C`) |
| `color_background_alt` | `#060A0D` | Login screen backdrop (dark photo, near-black) | measured |
| `color_sidebar_bg` | `#121116` | Sidebar panel background (all inner screens) | measured (`#0D0C0F`–`#131217` across screens — use one value) |
| `color_card_bg` | `#161618` | Login card, header card, title bars under posters | measured |
| `color_card_bg_2` | `#171719` | My Playlist header card, series poster title bar | measured |
| `color_panel_dark` | `#0C0B10` | Bouquets column (Channels screen) | measured |
| `color_panel_mid` | `#222128` | Channels list column (Channels screen) | measured |
| `color_panel_video` | `#0E0D12` | Video preview panel (Channels screen) | measured |
| `color_input_bg` | `#29282B` | Text-field fill (Login) | measured |
| `color_input_border` | `#55565A` | Text-field outline (Login) | estimated |
| `color_chip_inactive_bg` | `#26272B` | Unselected genre chips (Movies / Series) | measured (`#25262A`) |
| `color_button_secondary_bg` | `#212025` | "Plus d'infos" button (Home / Details) | measured |
| `color_button_secondary_border` | `#55565A` | Outline of "Plus d'infos" button | estimated |
| `color_progress_track` | `#333333` | Loading bar track; player seekbar track `#30312C` | measured |
| `color_text_primary` | `#FFFFFF` | All main text, titles, button labels | — |
| `color_text_secondary` | `#B0B0B5` | Descriptions, meta lines ("2024 • Drame • 2 saisons", hero synopsis) | estimated |
| `color_text_muted` | `#8A8A8E` | Unselected sidebar items, inactive tab, footer text, pending steps | estimated |
| `color_text_disabled` | `#6B6B70` | "Séries" pending step on Loading screen | estimated |
| `color_success_green` | `#31B561` | Green check + "Chaînes" done state (Loading) | measured |
| `color_icon_muted` | `#8E8E93` | Unselected sidebar icons (grey outline icons) | estimated |

### 1.2 Primary red (the brand colour — one value everywhere)

| Token | Hex | Where used | Source |
|---|---|---|---|
| `color_primary_red` | `#CC2226` | Primary buttons, selected chips, selected bouquet/channel rows, sidebar selected text + left bar, "EN DIRECT" badge, logo play icon | measured — login button `#CC252B`, Lecture `#C5191E`, bouquet `#C92D30`, channel `#C72E30`, chip `#C41716`, badge `#D32623` (range `#C41716`–`#D32623`; `#CC2226` is the centre value, use it everywhere for consistency) |
| `color_progress_fill` | `#E53935` | Progress/seekbar fill (brighter than the brand red) | measured — loading fill `#ED3B30`, player fill `#DE292B` |
| `color_selected_row_bg` | `#2A0A0C` | Dark-red tint behind the selected sidebar item (with a red left bar, see §1.4) | estimated from measured `#1D100E`–`#4A0F18` range — implement as `color_primary_red` at 18% opacity over `color_sidebar_bg` |
| `color_logo_play` | `#D32C2E` | Red rounded-triangle play mark in the "MH Player" logo | measured |

### 1.3 Radii & elevation

| Token | Value | Where used |
|---|---|---|
| `radius_card_large` | 20 dp | Login card, sidebar panel, hero banner |
| `radius_card` | 16 dp | Video panel, header card (My Playlist), channel tiles |
| `radius_button` | 12 dp | Primary button "Se connecter" / "Lecture" (slightly rounded rectangle, NOT pill) |
| `radius_chip` | 28 dp (full pill) | Genre chips "Tous / Action / …" |
| `radius_input` | 10 dp | Login text fields |
| `radius_poster` | 12 dp | Movie/series posters, episode thumbs |
| `radius_badge` | 10 dp | "EN DIRECT" badge (small rounded rect) |
| `radius_tab` | 8 dp | Login tabs (top corners of the tab strip) |
| Elevation / shadow | soft, ~8 dp blur, black 40% | Login card floats over a blurred dark photo backdrop; sidebar panel has a subtle shadow vs. the background |

### 1.4 Sidebar (shared by Home / Channels / Movies / Series / My Playlist / Details)

- **Width:** ~18% of screen width → **230 dp** at 1280 wide (mockups: 15–18%
  depending on screen; use 230 dp everywhere for consistency).
- **Position:** left edge, full height with ~16 dp top/bottom outer margin on
  Home (floating rounded panel) and edge-to-edge on Movies/Series/My Playlist
  (panel touches the top/bottom, with a 1 dp divider line `#1E1E22` on its
  right edge in Series/My Playlist/Details).
- **Logo row:** red rounded-square play icon (~36 dp) + "MH Player" bold
  ~22 sp white, top padding ~36 dp. (Home v3 uses a plain red triangle +
  text, no square; Channels/My Playlist/Details use the red rounded-square
  icon — use the **rounded-square** version everywhere.)
- **Menu items:** icon (outline style, ~24 dp) + label ~20 sp; row height
  ~64 dp; vertical gap between items ~16 dp; left padding ~24 dp.
  - Unselected: grey icon `#8E8E93` + grey-white text `#B0B0B5`.
  - **Selected: red icon + red bold text `#CC2226`, dark-red tinted row
    background (`color_selected_row_bg`) AND a 4 dp solid red vertical bar
    on the left edge of the row.** (Home v3 shows the red bar + red text;
    Channels shows the same; Movies/Series show the red bar + red text on a
    slightly lighter row.)
- **"Ma playlist" item:** pinned at the bottom of the sidebar (bookmark/list
  icon + label), same selected-state treatment when active.
- **Menu order (identical on every screen):** Accueil — Chaînes — Films —
  Séries — (bottom) Ma playlist.

### 1.5 Typography scale (sp at 720p)

| Style | Size | Weight | Use |
|---|---|---|---|
| `text_screen_title` | 68 sp | ExtraBold (800) | "Films", "Séries", "Ma playlist" page titles |
| `text_hero_title` | 48 sp | ExtraBold (800) | "Film à la une", "Mystères de Paris" (Details: 56 sp), "Sport 1" (Channels/Player: 56 sp) |
| `text_section_title` | 28 sp | Bold (700) | "Films populaires", "Séries", "Chaînes", "Films", "Saison 1" |
| `text_card_title` | 20 sp | Bold (700) | Stats numbers "1 240 / 380 / 215" (32 sp ExtraBold), channel names in lists |
| `text_body` | 18 sp | Regular (400) | Hero synopsis, details synopsis, meta lines |
| `text_poster_title` | 16 sp | SemiBold (600) | Titles under posters ("L'Ombre du Port", "Épisode 1") |
| `text_sidebar_item` | 20 sp | Regular (400); Bold when selected | Sidebar labels |
| `text_button_primary` | 20 sp | Bold (700) | "Se connecter", "Lecture" |
| `text_button_secondary` | 20 sp | SemiBold (600) | "Plus d'infos" |
| `text_chip` | 19 sp | SemiBold (600) | Genre chips |
| `text_badge` | 16 sp | Bold (700) | "EN DIRECT" (white on red) |
| `text_input_label` | 18 sp | Regular (400) | Field labels above inputs |
| `text_input_value` | 18 sp | Regular (400) | Field content / placeholder |
| `text_time` | 18 sp | Regular (400) | Player timecodes "14:32", "1:45:00" |

---

## 2. Screen 1 — Login (`login-approved.png`)

**Layout (1280×720):** dark blurred photo backdrop (near-black `#060A0D`).
Centred card: x ≈ 375–905 dp (width **~530 dp**, 41% of width), y ≈ 40–645 dp
(height ~605 dp). Card bg `#161618`, radius 20 dp, soft shadow.

- **Logo:** red play triangle (~44 dp) + "MH Player" white ExtraBold ~56 sp,
  centred, top of card (~48 dp padding).
- **Tabs (2):** full-width strip directly under the logo, height ~56 dp.
  Active tab "Xtream Codes": transparent bg, white bold text ~20 sp, with a
  **3 dp red underline** (`#CC2226`) centred under the text, width ~60% of
  the tab. Inactive tab "Playlist M3U": grey fill `#444444`, grey text
  `#8A8A8E`. (Note: in the mockup the active tab sits on the card bg and the
  inactive tab is a grey block — reproduce exactly that.)
- **Fields (4, identical):** label above (~18 sp, `#FFFFFF`), input below:
  height ~56 dp, fill `#29282B`, 1 dp border `#55565A`, radius 10 dp,
  text ~18 sp white; placeholder text grey `#8A8A8E`.
- **Buttons:** "Se connecter" — full card width minus 32 dp side padding,
  height ~64 dp, bg `#CC2226`, radius 12 dp, white bold ~20 sp, centred.
- **Footer:** "Connexion Xtream Codes" centred below the button, ~16 sp,
  grey `#8A8A8E`.
- **Exact French texts:** `MH Player` · `Xtream Codes` · `Playlist M3U` ·
  `Serveur (URL)` · `https://serveur.example.com` · `Nom d'utilisateur` ·
  `ex: utilisateur123` · `Mot de passe` · `••••••••••` · `Nom de la playlist` ·
  `ex: Ma playlist` · `Se connecter` · `Connexion Xtream Codes`.
- **Field order:** Serveur (URL) → Nom d'utilisateur → Mot de passe →
  Nom de la playlist → Se connecter.
- **Playlist M3U tab (not visible in this mockup):** same card, same 4-field
  pattern with URL + playlist-name fields — keep identical styling.

## 3. Screen 2 — Loading (`media-generation-iptv-loading-v1-0-b17634ea-d524-4a22-aa89-0415ffa406c7.png`)

**Layout:** full-screen dark backdrop with soft red radial glows
(near-black `#090909` with dark-red blurred blobs). All content centred.

- **Logo:** red play triangle + "MH Player" white ExtraBold ~64 sp, top
  centre, y ≈ 60–140 dp.
- **Progress ring:** centred, diameter **~220 dp**, stroke ~14 dp.
  Track: dark red/charcoal; arc: red `#D2332B` for the completed part,
  white `#FFFFFF` for the remaining part (the mockup shows a red arc on top
  and a white arc below — reproduce: ring = white full circle with a red
  progress arc on top, rounded caps).
- **Linear progress bar:** width **~360 dp** (28% of screen width), height
  8 dp, radius 4 dp, track `#333333`, fill `#E53935` (measured `#ED3B30`).
  Placed ~24 dp under the ring.
- **Percentage:** "62%" white Bold ~32 sp, ~16 dp under the bar.
- **Status line:** "Chargement des films…" white Regular ~28 sp, ~12 dp
  under the percentage. (Text changes per step: "Chargement des chaînes…",
  "Chargement des films…", "Chargement des séries…".)
- **Steps row:** centred, ~48 dp under the status line, three items with
  ~64 dp gaps: done = green filled check circle `#31B561` + green-grey text
  "Chaînes"; in progress = solid red dot + red bold text "Films"; pending =
  grey hollow circle + grey text "Séries". Item text ~28 sp Bold.
- **Footer:** "Ma playlist" centred near the bottom, ~20 sp, grey `#6B6B70`.
- **Exact French texts:** `MH Player` · `62%` · `Chargement des films…` ·
  `Chaînes` · `Films` · `Séries` · `Ma playlist`.

## 4. Screen 3 — Home (`media-generation-iptv-home-v3-narrow-sidebar-0-2f7997f1-7b16-43db-8afa-d350f40c32c8.png`)

**Layout (1280×720):** bg `#0A0A0C`. Sidebar as §1.4 (floating rounded
panel, x ≈ 64–290 dp, y ≈ 56–660 dp). Content starts at x ≈ 320 dp
(25%), right margin ~32 dp.

- **Hero banner:** x ≈ 320–1180 dp (width ~860 dp, 67%), y ≈ 8–280 dp
  (height ~272 dp, 38% of height), radius 20 dp, photographic background
  with a left-side dark gradient scrim for text legibility.
  - Title "Film à la une" white ExtraBold 48 sp, at ~48 dp from hero left,
    ~56 dp from hero top.
  - Synopsis (2 lines, 18 sp, `#D8D8DC`): "Un thriller haletant dans une
    ville sous la pluie. Trahisons, secrets et enquête nocturne."
  - Buttons row under the synopsis, gap ~16 dp: "▶ Lecture" red
    `#C5191E`→ use `#CC2226`, height ~52 dp, padding ~24 dp horizontal,
    radius 12 dp, white bold 20 sp with small white play triangle;
    "Plus d'infos" secondary: bg `#212025` (semi-transparent dark),
    1 dp border `#55565A`, radius 12 dp, white SemiBold 20 sp.
- **Rows:** section title 28 sp Bold white, ~24 dp above the row.
  "Films populaires" row, then "Séries" row.
- **Poster grid:** 6 posters per row. Poster **~112 dp wide × ~168 dp high**
  (**aspect ratio 2:3**), radius 12 dp, horizontal gap ~16 dp, vertical gap
  between rows ~48 dp (title + row spacing). Posters are full-bleed images,
  no title text on the poster itself in this screen (except baked-in poster
  artwork text).
- **Exact French texts:** `MH Player` · `Accueil` · `Chaînes` · `Films` ·
  `Séries` · `Ma playlist` · `Film à la une` ·
  `Un thriller haletant dans une ville sous la pluie. Trahisons, secrets et enquête nocturne.` ·
  `Lecture` · `Plus d'infos` · `Films populaires` · `Séries`.
- **Selected sidebar item:** "Accueil" (red text + red left bar).

## 5. Screen 4 — Channels (`media-generation-iptv-channels-v3-red-selected-0-c666135f-00c5-48be-a9b3-3f228750f3ae.png`)

**Layout (1280×720):** bg `#0A0A0C` (edges `#1B1A21` in the mockup's outer
margin — use `#0A0A0C`). Four columns, no gaps between panels except ~16 dp
gutters:

| Column | x (dp) | Width | Bg |
|---|---|---|---|
| Sidebar | 24–222 | ~198 dp | `#121116` |
| Bouquets | 244–438 | ~194 dp | `#0C0B10` |
| Chaînes (list) | 462–664 | ~202 dp | `#222128` |
| Video preview | 678–1256 | ~578 dp (45%) | `#0E0D12` |

- **Column headers:** "Bouquets" / "Chaînes" white SemiBold ~20 sp,
  ~24 dp top padding, ~24 dp left padding.
- **Bouquet rows:** height ~64 dp, text ~20 sp. **Selected bouquet
  ("Sports"): FULL red background `#C92D30` → use `#CC2226`, radius 12 dp,
  white bold text, inset ~8 dp from column edges.** Unselected bouquets:
  white Regular text on transparent bg.
- **Channel rows:** height ~64 dp; each row = small round channel logo
  (~28 dp, coloured circle) + name ~20 sp white. **Selected channel
  ("Sport 1"): FULL red background `#C72E30` → use `#CC2226`, square
  corners (fills the whole column width, no radius, no inset), white bold
  text.** Unselected: transparent bg, white Regular text.
- **Video panel:** radius 16 dp. Inside, 16:9 video area on top
  (x ≈ 694–1240 dp, y ≈ 72–392 dp), radius 12 dp, with a seekbar overlaid
  at its bottom: track `#30312C` 4 dp, fill `#E53935`, white round thumb
  (~12 dp) at ~35% progress.
- **Transport row (under video):** play icon in a dark rounded square
  (~36 dp, bg `#1E1E22`) + current time "14:32" white 18 sp on the left;
  total "1:45:00" white 18 sp + volume icon + fullscreen icon on the right.
- **Channel title block:** "Sport 1" white ExtraBold 56 sp + red badge
  "EN DIRECT" (bg `#D32623` → use `#CC2226`, white bold 16 sp, radius
  10 dp, padding 12 dp × 6 dp) on the same baseline, ~24 dp under the
  transport row. Subtitle "Match de football" grey `#B0B0B5` 20 sp below.
- **Exact French texts:** `MH Player` · `Accueil` · `Chaînes` · `Films` ·
  `Séries` · `Ma playlist` · `Bouquets` · `Sports` · `Actualités` · `Cinéma` ·
  `Jeunesse` · `Documentaires` · `Musique` · `Chaînes` · `Sport 1` ·
  `Sport 2` · `Foot Live` · `Tennis Live` · `Basket Live` · `Sports News` ·
  `14:32` · `1:45:00` · `EN DIRECT` · `Match de football`.
- **Selected sidebar item:** "Chaînes".

## 6. Screen 5 — Movies (`media-generation-iptv-movies-v1-0-81b6dd44-3240-4473-99f1-895fcc086c7b.png`)

**Layout:** bg `#0A0A0C`. Sidebar as §1.4 (edge-to-edge, width ~230 dp).
Content x ≈ 270–1256 dp, top margin ~32 dp.

- **Page title:** "Films" white ExtraBold 68 sp, top-left of content.
- **Genre chips row:** ~32 dp under the title, gap ~12 dp, height ~40 dp.
  Chip: pill radius 28 dp, padding ~24 dp horizontal, text 19 sp SemiBold.
  **Selected chip ("Tous"): bg `#CC2226`, white text. Unselected: bg
  `#26272B`, white text.**
- **Poster grid:** 6 columns × 2 rows visible. Poster **~137 dp wide ×
  ~188 dp high (aspect ratio 2:3)**, radius 12 dp, horizontal gap ~20 dp,
  vertical gap ~56 dp (includes title). **Title is plain white text
  16 sp SemiBold BELOW the poster** (on the background, no bar), centred
  under each poster, ~10 dp gap.
- **Exact French texts:** `MH Player` · `Accueil` · `Chaînes` · `Films` ·
  `Séries` · `Ma playlist` · `Films` (page title) · `Tous` · `Action` ·
  `Comédie` · `Drame` · `Science-fiction` · `Thriller` ·
  `L'Ombre du Port` · `Rendez-vous à Paris` · `La Nuit Écarlate` ·
  `Chasse Silencieuse` · `Le Dernier Train` · `Secrets de Marseille` ·
  `Rivage Perdu` · `Échos du Passé` · `Cible 47` · `Vol de Minuit` ·
  `Cœur de Glace` · `La Promesse Oubliée`.
- **⚠️ KNOWN FLAW — DO NOT REPRODUCE:** in this mockup the sidebar
  contains **"Films" twice** — once with a film-reel icon (unselected,
  grey) and once with a clapperboard icon (selected, red). In the real app
  the sidebar must contain **"Films" exactly once** (clapperboard icon),
  in the standard order Accueil — Chaînes — Films — Séries — Ma playlist.
  The same applies to the "Chaînes" row, which uses a satellite-dish icon
  here but a TV icon elsewhere — use the **TV icon** for Chaînes
  everywhere (as on Home/Channels/Details).

## 7. Screen 6 — Series (`media-generation-iptv-series-v1-0-746fdcf3-989e-46a2-a338-34ad62ddf0de.png`)

**Layout:** identical grid structure to Movies, bg `#080808` → use
`#0A0A0C`. Sidebar edge-to-edge with a 1 dp right divider `#1E1E22`.

- **Page title:** "Séries" white ExtraBold 68 sp.
- **Genre chips row:** same as Movies. Selected "Toutes" red `#CC2226`;
  unselected `#26272B`.
- **Poster grid:** 6 columns × 2 rows. Poster **~137 dp wide × ~188 dp
  high (aspect ratio 2:3)**, radius 12 dp top corners only — **each poster
  is a card: image on top + a dark label bar (`#171719`) below, ~36 dp
  high, with the title in white 16 sp SemiBold centred in the bar.**
  (Difference vs. Movies: Movies titles sit directly on the background;
  Series titles sit inside a dark bar. Keep each screen's own treatment.)
  Horizontal gap ~20 dp, vertical gap ~40 dp.
- **Exact French texts:** `MH Player` · `Accueil` · `Chaînes` · `Films` ·
  `Séries` · `Ma playlist` · `Séries` (page title) · `Toutes` · `Drame` ·
  `Policier` · `Comédie` · `Science-fiction` · `Aventure` ·
  `Les Ombres du Havre` · `Minuit à Marseille` · `Le Secret du Phare` ·
  `Liaisons Dangereuses` · `Évasion en Alpes` · `La Nuit Blanche` ·
  `Rumeurs d'Orléans` · `Au Bord du Silence` · `Piste Inconnue` ·
  `Cœur de Glace` · `L'Enclave Oubliée` · `Sous la Lune Rouge`.
- **Selected sidebar item:** "Séries" (clapperboard icon, red text + red
  left bar). Note: in this mockup "Films" also uses a clapperboard icon —
  the icon set is inconsistent across mockups; canonical icons: Accueil =
  home, Chaînes = TV, Films = clapperboard, Séries = TV-with-grid (as on
  Channels screen) — pick one set and use it on all screens.

## 8. Screen 7 — My Playlist (`media-generation-iptv-myplaylist-v1-0-dce09e84-9c0f-4b80-8131-5d98e1ac8733.png`)

**Layout:** bg `#090909` → use `#0A0A0C`. Sidebar edge-to-edge, 1 dp right
divider. Content x ≈ 270–1256 dp.

- **Page title:** "Ma playlist" white ExtraBold 68 sp.
- **Header card:** x ≈ 270–1196 dp, y ≈ 116–232 dp (height ~116 dp),
  bg `#171719`, radius 16 dp, with a **4 dp solid red vertical bar
  (`#CC2226`) on its left edge** (inset ~16 dp from the card's left edge,
  full card height minus padding).
  - Left: "Ma playlist" white Bold 32 sp + subtitle "Serveur : mh-iptv.fr
    • Premium" grey `#B0B0B5` 18 sp below it.
  - Right: three stats, each = big number white ExtraBold 32 sp over a
    grey label 18 sp: "1 240 / chaînes", "380 / films", "215 / séries",
    spaced ~96 dp apart.
  - Bottom-right inside the card: "Dernière mise à jour : aujourd'hui"
    grey `#8A8A8E` 16 sp.
- **Section "Chaînes":** title 28 sp Bold, ~24 dp under the header card.
  Row of 6 **icon tiles**: each **~150 dp wide × ~64 dp high**, bg
  `#171719`, radius 12 dp, gap ~16 dp. Tile content: coloured icon
  (~36 dp, centred top: blue football, blue swirl, green pitch, gold
  clapperboard, red TV, orange/purple music note) + white SemiBold 16 sp
  label centred below it.
- **Sections "Films" / "Séries":** title 28 sp Bold. Row of 6 **landscape
  thumbs, 16:9 aspect ratio (~150 dp × 84 dp)**, radius 12 dp, gap ~16 dp,
  with the title in white SemiBold 16 sp overlaid at the bottom centre of
  the image (on a subtle bottom gradient scrim).
- **Exact French texts:** `MH Player` · `Accueil` · `Chaînes` · `Films` ·
  `Séries` · `Ma playlist` · `Serveur : mh-iptv.fr • Premium` · `1 240` ·
  `chaînes` · `380` · `films` · `215` · `séries` ·
  `Dernière mise à jour : aujourd'hui` · `Sport 1` · `Sport 2` ·
  `Foot Live` · `Cinéma 1` · `Infos 24` · `Musique 1` · `L'Évasion` ·
  `Nuit Noire` · `Les Sommets` · `Code Rouge` · `Hors Piste` · `Été 22` ·
  `Mystères de Paris` · `Orbite 9` · `La Résidence` · `Silence` ·
  `La Cour` · `Coloc & Co`.
- **Selected sidebar item:** "Ma playlist" (red text + red left bar,
  list icon).

## 9. Screen 8 — Fullscreen Player (`media-generation-iptv-player-fullscreen-v1-0-4cff5238-dc3a-4d8a-abe7-4b06da8dafad.png`)

**Layout:** full-bleed 16:9 video fills the whole 1280×720 canvas. Two
translucent overlay bars (dark gradient scrim, ~black 55% → transparent),
auto-hidden after a few seconds of inactivity.

- **Top bar:** height ~116 dp, gradient from black 65% at the top to
  transparent. Content: back arrow (white chevron-left, ~40 dp) at left
  (~40 dp margin) + "Sport 1" white ExtraBold 56 sp + red badge
  "EN DIRECT" (bg `#CC2226`, white bold 16 sp, radius 10 dp) on the same
  baseline, ~24 dp gap after the title.
- **Bottom bar:** height ~96 dp, gradient from transparent to black 65%.
  Content (left → right): pause button = white circle outline (~56 dp
  diameter, 3 dp stroke, pause glyph white, centred) at ~40 dp left margin;
  current time "14:32" white 18 sp; seekbar (flexible, ~24 dp gaps):
  track `#30312C` 6 dp high, radius 3 dp, fill `#E53935`, white round thumb
  ~16 dp diameter at ~60% progress; total time "1:45:00" white 18 sp;
  volume icon, settings (gear) icon, fullscreen icon — white outline icons
  ~28 dp, gaps ~28 dp, right margin ~40 dp.
- **Exact French texts:** `Sport 1` · `EN DIRECT` · `14:32` · `1:45:00` ·
  `MH Player` (see flaw note below).
- **⚠️ KNOWN FLAW — DO NOT REPRODUCE (unless the design says so):** the
  mockup shows a small grey **"MH Player" text label at the top-right
  corner** of the top bar. It was NOT in the brief and is not part of the
  player UI — **omit it** in the real app (top-right stays empty).

## 10. Screen 9 — Details (`media-generation-iptv-details-v1-0-ba64742f-6315-4d26-bd24-81b8a6ee94ae.png`)

**Layout:** bg `#0A0A0C`. Sidebar edge-to-edge (~230 dp) with 1 dp right
divider. Content x ≈ 270–1256 dp, top margin ~40 dp.

- **Poster (left):** x ≈ 270–600 dp (width ~330 dp), y ≈ 40–428 dp
  (height ~388 dp), **aspect ratio ~0.85 (≈ 4:5 portrait)**, radius 12 dp,
  full-bleed image.
- **Info block (right of poster):** x ≈ 640–1256 dp.
  - Title "Mystères de Paris" white ExtraBold 56 sp.
  - Meta line "2024 • Drame • 2 saisons" grey `#B0B0B5` 20 sp SemiBold,
    ~16 dp under the title.
  - Synopsis "Un inspecteur parisien traque un secret enfoui au cœur de
    la capitale." white/light-grey 20 sp Regular, 2 lines, ~24 dp under
    the meta line.
  - Buttons row, ~32 dp under the synopsis, gap ~16 dp: "▶ Lecture" red
    `#CC2226` (measured `#C51F1E`), height ~64 dp, padding ~40 dp
    horizontal, radius 12 dp, white bold 20 sp; "Plus d'infos" secondary
    bg `#212025`, border `#55565A`, radius 12 dp, white SemiBold 20 sp,
    same height.
- **Episodes section:** title "Saison 1" white Bold 28 sp, ~48 dp under
  the poster/info block. Row of 6 **episode cards, 16:9 thumbs
  (~150 dp × 84 dp)** + label bar below each thumb (~36 dp high,
  bg `#151517`, radius 0–0–12–12 dp): "Épisode 1" … "Épisode 6", white
  16 sp SemiBold. **Selected episode ("Épisode 1"): label text in red
  `#CC2226` (bold); unselected episodes: white text.** Gap ~16 dp.
- **Exact French texts:** `MH Player` · `Accueil` · `Chaînes` · `Films` ·
  `Séries` · `Ma playlist` · `Mystères de Paris` ·
  `2024 • Drame • 2 saisons` ·
  `Un inspecteur parisien traque un secret enfoui au cœur de la capitale.` ·
  `Lecture` · `Plus d'infos` · `Saison 1` · `Épisode 1` · `Épisode 2` ·
  `Épisode 3` · `Épisode 4` · `Épisode 5` · `Épisode 6`.
- **Selected sidebar item:** "Séries" (red text + red left bar).

---

## 11. Cross-screen rules & inconsistencies to resolve

1. **One red, one background.** The mockups drift (`#C41716`–`#D32623` reds,
   `#080808`–`#131217` darks). The app uses exactly `color_primary_red
   #CC2226` and `color_background #0A0A0C` everywhere; the measured ranges
   above are the tolerance the mockups were sampled from, not separate
   tokens.
2. **Sidebar width is 230 dp on every inner screen** (the "narrow sidebar"
   approved for Home v3). The Channels screen's three extra columns are
   ~195–205 dp each; the video column takes the rest.
3. **Selected states — two patterns, do not mix them up:**
   - Sidebar item / episode label → red **text** + red left bar (+ dark-red
     tinted row bg in the sidebar).
   - Bouquet row / channel row / genre chip / primary button → **full red
     background** + white text.
4. **Poster aspect ratios:** portrait posters (Home rows, Movies, Series)
   are **2:3**; landscape thumbs (My Playlist Films/Séries rows, Details
   episodes) are **16:9**; the Details hero poster is ~4:5.
5. **Titles:** Movies titles sit directly on the background below the
   poster; Series titles sit in a dark bar below the poster — keep each
   screen's own treatment (do not unify).
6. **Icons:** use one consistent outline-icon set (home / TV / clapperboard /
   TV-grid / bookmark-list). The mockups swap the Chaînes icon (TV vs.
   satellite dish vs. broadcast) and the Films icon (reel vs. clapperboard)
   between screens — canonical: **Chaînes = TV, Films = clapperboard**.
7. **"Films" appears twice in the Movies mockup sidebar — reproduce it
   only once** (see §6).
8. **The "MH Player" corner label in the fullscreen player mockup is not
   part of the design — omit it** (see §9).
9. **Language:** all UI strings are French (see the exact-text lists per
   screen above). No English UI text anywhere except the brand name
   "MH Player".

---

## 12. Quick reference (copy-paste)

```
Background ......... #0A0A0C
Sidebar bg ......... #121116
Card bg ............ #161618 / #171719
Panel dark / mid ... #0C0B10 / #222128
Input bg / border .. #29282B / #55565A
Chip inactive ...... #26272B
Secondary button ... #212025 (border #55565A)
Primary red ........ #CC2226   (sampled #C41716–#D32623)
Progress fill ...... #E53935
Progress track ..... #333333
Text primary ....... #FFFFFF
Text secondary ..... #B0B0B5
Text muted ......... #8A8A8E
Success green ...... #31B561
Selected row tint .. #CC2226 @ 18% over sidebar bg + 4dp red left bar
Sidebar width ...... 230 dp
Poster (portrait) .. 2:3, radius 12 dp
Thumb (landscape) .. 16:9, radius 12 dp
Screen title ....... 68 sp ExtraBold
Section title ...... 28 sp Bold
Button ............. height 52–64 dp, radius 12 dp
Chip ............... height 40 dp, pill radius 28 dp
```

*Generated 2026-11-25 from the 9 approved mockups. Colours were sampled
from the PNG pixels; dimensions were measured off the 2048×1152 renders and
scaled ÷1.6 to the 1280×720 baseline. Where the mockups disagree, §11 states
the canonical value.*
