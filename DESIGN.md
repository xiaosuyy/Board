---
name: Board Keyboard
version: "1.0"
description: A quiet, high-contrast Android IME. Black / white / grey only, one blue accent, no gradients.
source_of_truth:
  - android/app/src/main/res/values/colors.xml
  - android/app/src/main/res/values/dimens.xml
  - android/app/src/main/java/com/board/ime/ui/KeyboardTheme.kt

colors:
  # ---- neutral scale (single source of grey) ----
  neutral-050: "#F7F8F9"
  neutral-100: "#EDEFF1"
  neutral-200: "#E2E5E8"
  neutral-300: "#D2D6DA"
  neutral-400: "#B4B9BE"
  neutral-500: "#8A9199"
  neutral-600: "#6B7075"
  neutral-700: "#4A5056"
  neutral-800: "#2A2D31"
  neutral-850: "#1E2124"
  neutral-900: "#16181A"
  neutral-950: "#0E1012"

  # ---- accent (used sparingly) ----
  accent-light: "#2F6BFF"
  accent-light-container: "#E8EFFF"
  accent-dark: "#5B8CFF"
  accent-dark-container: "#1D2735"

  # ---- light keyboard palette ----
  light-background: "#E9EBEE"
  light-key: "#FFFFFF"
  light-key-pressed: "#D8DBDF"
  light-key-functional: "#D3D7DC"
  light-key-functional-pressed: "#BFC4CA"
  light-key-text: "#111418"
  light-key-text-functional: "#3C4248"
  light-accent: "#2F6BFF"
  light-on-accent: "#FFFFFF"
  light-divider: "#C9CDD2"
  light-candidate-bar: "#F7F8F9"
  light-candidate-text: "#111418"
  light-candidate-muted: "#9AA0A6"
  light-candidate-selected-bg: "#E8EFFF"
  light-candidate-selected-text: "#2F6BFF"
  light-mode-chip-bg: "#E2E5E8"
  light-mode-chip-text: "#4A5056"
  light-panel-surface: "#FFFFFF"
  light-panel-surface-pressed: "#E6E9EC"
  light-pinyin-bg: "#E7EAEE"
  light-icon: "#3C4248"

  # ---- dark keyboard palette ----
  dark-background: "#0E1012"
  dark-key: "#2A2D31"
  dark-key-pressed: "#3C4046"
  dark-key-functional: "#1E2124"
  dark-key-functional-pressed: "#34383D"
  dark-key-text: "#F2F4F6"
  dark-key-text-functional: "#C3C9CF"
  dark-accent: "#5B8CFF"
  dark-on-accent: "#0E1012"
  dark-divider: "#24272A"
  dark-candidate-bar: "#16181A"
  dark-candidate-text: "#F2F4F6"
  dark-candidate-muted: "#7C838A"
  dark-candidate-selected-bg: "#1D2735"
  dark-candidate-selected-text: "#7FA6FF"
  dark-mode-chip-bg: "#2A2D31"
  dark-mode-chip-text: "#C3C9CF"
  dark-panel-surface: "#2A2D31"
  dark-panel-surface-pressed: "#3C4046"
  dark-pinyin-bg: "#2F3338"
  dark-icon: "#D7DCE1"

typography:
  key-label:
    fontFamily: system
    fontSize: 20sp
    fontWeight: 400
  key-label-function:
    fontFamily: system
    fontSize: 15sp
    fontWeight: 400
  title-l:
    fontFamily: system
    fontSize: 15sp
    fontWeight: 500
  body-m:
    fontFamily: system
    fontSize: 13sp
    fontWeight: 400
  label-s:
    fontFamily: system
    fontSize: 12sp
    fontWeight: 400
  label-xs:
    fontFamily: system
    fontSize: 10sp
    fontWeight: 400
  candidate-standard:
    fontFamily: system
    fontSize: 19sp
    fontWeight: 400
  pinyin-standard:
    fontFamily: system
    fontSize: 16sp
    fontWeight: 400
  candidate-english-gloss:
    fontFamily: system
    fontSize: 11sp
    fontWeight: 400

spacing:
  xs: 2dp
  s: 4dp
  m: 8dp
  l: 12dp
  xl: 16dp

rounded:
  sm: 6dp
  md: 10dp
  lg: 14dp

sizing:
  icon-sm: 14dp
  icon-md: 18dp
  icon-lg: 20dp
  key-height: 50dp
  key-gap-h: 6dp
  key-gap-v: 8dp
  top-panel-height: 56dp
  candidate-chip-height: 32dp
  candidate-min-width: 40dp
  tool-row-height: 44dp
  tool-hit-size: 44dp

elevation:
  strategy: flat
  shadow: none
  separation: color-and-1dp-divider

components:
  key:
    background: "{colors.light-key}"
    textColor: "{colors.light-key-text}"
    rounded: "{rounded.md}"
    height: "{sizing.key-height}"
  key-functional:
    background: "{colors.light-key-functional}"
    textColor: "{colors.light-key-text-functional}"
    rounded: "{rounded.md}"
  candidate-chip:
    background: transparent
    selectedBackground: "{colors.light-candidate-selected-bg}"
    selectedTextColor: "{colors.light-candidate-selected-text}"
    textColor: "{colors.light-candidate-text}"
    rounded: "{rounded.sm}"
    height: "{sizing.candidate-chip-height}"
  pinyin-pill:
    background: "{colors.light-pinyin-bg}"
    textColor: "{colors.light-candidate-text}"
    rounded: "{rounded.sm}"
    height: "{sizing.candidate-chip-height}"
  top-panel:
    background: "{colors.light-panel-surface}"
    height: "{sizing.top-panel-height}"
    divider: "{colors.light-divider}"
  clipboard-card:
    background: "{colors.light-panel-surface}"
    rounded: "{rounded.md}"
    minHeight: 48dp
  settings-card:
    background: "{colors.light-panel-surface}"
    rounded: "{rounded.lg}"
    padding: 16dp
---

# Design System: Board Keyboard

**What this file is.** A machine-readable + human-readable description of Board's visual
identity, written to the open `DESIGN.md` format (Google Stitch / `google-labs-code/design.md`).
The YAML front matter above carries the exact tokens; the prose below explains *why* they exist.
Read this before generating or changing any UI in this project, and keep it in sync with the
real token files listed under `source_of_truth`.

**How to read it.** `{colors.*}` / `{rounded.*}` / `{spacing.*}` / `{sizing.*}` reference tokens in
the front matter. Hex values are authoritative. The `light-*` / `dark-*` palettes are two sides of
the same semantic roles and must always be changed together.

## 1. Overview

Board is a **quiet, high-contrast, flat** input method. It borrows Gboard's *usage logic*
(layered keyboard, one-line candidate strip, language shown on the spacebar, double-tap Shift to
lock, long-press Backspace to delete) but deliberately strips the *visual noise*: no gradients, no
glows, no large fields of color, no drop shadows.

The atmosphere is **utilitarian and calm**. The keyboard is a tool you read at a glance, so the
system optimizes for legibility and restraint over decoration. The page (or app) behind the
keyboard is the loud part; the keyboard stays out of the way.

Three rules define the whole look:

1. **Black / white / grey only.** The entire neutral scale is the palette.
2. **One accent, used sparingly.** Blue marks only the *current selection* and the *active state*
   (selected candidate, Shift active, focused control). Never as a background wash.
3. **Flat by default.** Depth is communicated with color contrast and a 1dp divider — never with
   shadows. (See §5.)

## 2. Colors

### Neutral scale
A single grey ramp (`neutral-050` → `neutral-950`) is the backbone. Both the light and dark
keyboard palettes are built from it, so greys never drift. `neutral-050`/`neutral-100` are the
light surfaces; `neutral-900`/`neutral-950` are the dark surfaces.

### Accent
- **Accent** (`#2F6BFF` light / `#5B8CFF` dark): the *only* chromatic color. Functional role:
  selected candidate text + background tint, active Shift, focused input, sync status, search.
- **Accent container** (`#E8EFFF` light / `#1D2735` dark): the soft tint behind a selected
  candidate or an accent chip. Always paired with accent text on top.
- **On-accent** (`#FFFFFF` light / `#0E1012` dark): text/icons placed on a solid accent fill.

### Light keyboard palette
- `light-background` (`#E9EBEE`): the board behind the keys.
- `light-key` (`#FFFFFF`) / `light-key-pressed` (`#D8DBDF`): normal keys and their pressed state.
- `light-key-functional` (`#D3D7DC`) / `light-key-functional-pressed` (`#BFC4CA`): Shift, Backspace,
  language, Enter and other non-letter keys — deliberately a touch darker so the letter field reads
  as the foreground.
- `light-candidate-bar` (`#F7F8F9`): the candidate strip background (slightly off-white so the
  white pills/candidates sit on top).
- `light-candidate-muted` (`#9AA0A6`): hints and secondary candidate text.
- `light-pinyin-bg` (`#E7EAEE`): the composing (pinyin) pill — must stay visibly darker than the
  bar so the user sees exactly which text is still being composed.
- `light-divider` (`#C9CDD2`): the single 1dp separator between panel and content.

### Dark keyboard palette
The dark side mirrors every light role: `dark-background` (`#0E1012`) behind `dark-key`
(`#2A2D31`), functional keys lifted to `dark-key-functional` (`#1E2124`), text at
`dark-key-text` (`#F2F4F6`). The accent brightens to `#5B8CFF` for contrast on dark. `dark-on-accent`
is dark (`#0E1012`) because the accent is now the *light* element.

### Semantic roles
| Role | Light | Dark |
|---|---|---|
| Background | `#E9EBEE` | `#0E1012` |
| Key surface | `#FFFFFF` | `#2A2D31` |
| Functional key | `#D3D7DC` | `#1E2124` |
| Primary text | `#111418` | `#F2F4F6` |
| Muted text | `#9AA0A6` | `#7C838A` |
| Accent | `#2F6BFF` | `#5B8CFF` |
| Selected candidate bg | `#E8EFFF` | `#1D2735` |
| Divider | `#C9CDD2` | `#24272A` |

## 3. Typography

The system uses the platform font (no custom typeface). Weight carries hierarchy: **medium (500)**
for titles/section headers, **regular (400)** for everything else. There is no bold, no italics.

- **Key label** — 20sp. The large letter on each key. Function keys drop to **15sp**
  (`key-label-function`) because their labels are words (Shift, 123, 中/英), not single glyphs.
- **Title / section header** — 15sp medium.
- **Body** — 13sp. Clipboard items, panel text.
- **Label** — 12sp; **micro-label** — 10sp (More-panel captions).
- **Candidate text** is *user-adjustable* (设置 → 外观 → 候选词字号): four steps —
  小 16sp / 标准 19sp / 大 22sp / 特大 25sp — each with a matched pinyin size (14/16/17/18sp),
  horizontal padding (12/14/16/18dp) and English-gloss size (10/11/12/13sp). Sizes are in **sp**
  so they follow the system font scale.
- **Candidate English gloss** (optional) sits below the Chinese candidate at a clearly smaller
  size; it is auxiliary information, never the primary label.

## 4. Layout

Spacing is a single 2/4/8/12/16dp ramp (`spacing.xs…xl`). Nothing uses an off-ramp value.

- **Keyboard:** 50dp key height, 6dp horizontal / 8dp vertical gaps, 4dp horizontal and 8dp
  vertical board padding. Portrait fills the width; landscape/large screens use a floating,
  end-aligned card (see §5 for its elevation treatment).
- **Top panel:** a **single fixed 56dp band** shared by every panel state (toolbar, candidates,
  More, clipboard, voice, translation, phrases, expanded candidates). Internal content changes,
  the height never does — so the input method never jumps when switching states. 10dp horizontal
  padding.
- **Candidate strip:** one line; the composing pill and candidate chips are a compact 32dp tall
  inside the 56dp band, with a 40dp minimum chip width and a 40dp action zone on the right for the
  `∨ / ∧` expand button.
- **Toolbar:** a 44dp icon row centered in the 56dp band; 20dp icons, 44dp hit targets.
- **Content panels** (clipboard, translation, phrases, expanded candidates) occupy exactly the
  keyboard's height and are mutually exclusive with it, so total height is constant. Clipboard
  search is the one exception: results are capped at 146dp and the keyboard stays visible below.
- **Settings screen:** 20dp screen padding, 16dp card padding, 16dp section gap.

## 5. Elevation & Depth

**The system is flat.** There are no drop shadows anywhere — not on keys, cards, or the floating
keyboard. Depth is expressed two ways only:

1. **Color contrast** — a raised surface is simply a lighter (light theme) or lighter-than-background
   (dark theme) fill: key on background, card on background, panel on content.
2. **A single 1dp divider** (`divider` token) separates the top panel from the content below.

Pressed states are a *darker fill*, not a shadow or a scale animation. The floating (landscape)
keyboard keeps the same flat treatment; it is distinguished by its rounded card shape and edge
margin, not by elevation.

## 6. Shapes

Exactly **three** corner radii exist (`rounded.sm/md/lg`) — there is no per-component radius.

- **6dp (sm):** candidate chips, the pinyin pill, small pressed-state blocks, action buttons.
- **10dp (md):** keys and clipboard cards.
- **14dp (lg):** the top panel, the floating keyboard card, settings cards.

Rule: **never mix rounded and sharp corners in the same view.** A pill/circle (e.g. a fully round
icon button) is only used where the platform control already implies it, and never next to a
squared element in the same row.

## 7. Components

- **Keys** — 10dp rounded rectangles, 50dp tall. Letters: white (`light-key`) with
  `light-key-text`. Function keys (Shift / Backspace / 123 / language / Enter): a darker
  `light-key-functional` fill with `light-key-text-functional`. Pressed = darker fill, no shadow.
- **Candidate chip** — transparent by default; the **selected** (first) candidate gets an
  `accent-container` rounded-6dp background with accent text. Chips are a compact 32dp visual
  inside the 56dp panel (the tap target is the full band height).
- **Pinyin pill** — the composing text, on a `pinyin-bg` rounded-6dp pill that is clearly darker
  than the candidate bar so the still-composing text is obvious.
- **Top panel** — a 14dp-rounded surface (light: white; dark: `panel-surface`) holding whichever
  header is active; its bottom edge is the single 1dp divider.
- **Toolbar / More** — flat icon buttons, 20dp/18dp icons, 44dp hit targets, accent only when
  active.
- **Clipboard card** — 10dp rounded, white surface, 48dp minimum height, flat (no shadow); the
  action row (favorite / pin / delete) sits inside the card, not in a floating menu.
- **Expanded candidate grid** — a 4-column flat grid filling the keyboard area; each cell is the
  candidate label, the first cell carries the selected accent background. Same chips, just tiled.
- **Settings card** — 14dp rounded, 16dp padding.

## 8. Do's and Don'ts

**Do**
- Do keep the palette to black / white / grey and treat blue as a rare accent (selection + active
  state only).
- Do use only the three radii (6 / 10 / 14dp) and the 2/4/8/12/16dp spacing ramp.
- Do change the light and dark palettes together, and keep `colors.xml`, `dimens.xml`,
  `KeyboardTheme.kt` and this file in sync.
- Do express depth with surface color and the single 1dp divider.
- Do keep the top panel at a fixed 56dp so the keyboard never jumps between states.
- Do show the still-composing (pinyin) text on its own, visibly darker pill.

**Don't**
- Don't add gradients, glows, or drop shadows.
- Don't fill large areas with the accent color.
- Don't introduce a new corner radius or an off-ramp spacing value.
- Don't mix rounded and sharp corners in one view.
- Don't use bold or italic; use weight (500 vs 400) for hierarchy.
- Don't hard-code colors/sizes in a View — read them from the theme tokens.
