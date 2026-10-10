---
version: alpha
name: Board
description: A quiet, flat, high-contrast Android IME + settings app. Black / white / grey only, one blue accent, no shadows.
source_of_truth:
  - android/app/src/main/res/values/colors.xml
  - android/app/src/main/res/values-night/colors.xml
  - android/app/src/main/res/values/dimens.xml
  - android/app/src/main/res/values/themes.xml
  - android/app/src/main/java/com/board/ime/ui/KeyboardTheme.kt
  - android/app/src/main/java/com/board/ime/settings/SettingsUi.kt

colors:
  # ---- single grey ramp (both palettes are built from it) ----
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

  accent-light: "#2F6BFF"
  accent-light-container: "#E8EFFF"
  accent-dark: "#5B8CFF"
  accent-dark-container: "#1D2735"

  # ---- keyboard · light ----
  kb-light-background: "#E9EBEE"
  kb-light-key: "#FFFFFF"
  kb-light-key-pressed: "#D8DBDF"
  kb-light-key-functional: "#D3D7DC"
  kb-light-key-functional-pressed: "#BFC4CA"
  kb-light-key-text: "#111418"
  kb-light-key-text-functional: "#3C4248"
  kb-light-accent: "#2F6BFF"
  kb-light-on-accent: "#FFFFFF"
  kb-light-divider: "#C9CDD2"
  kb-light-candidate-bar: "#F7F8F9"
  kb-light-candidate-text: "#111418"
  kb-light-candidate-muted: "#9AA0A6"
  kb-light-candidate-selected-bg: "#E8EFFF"
  kb-light-candidate-selected-text: "#2F6BFF"
  kb-light-pinyin-bg: "#E7EAEE"
  kb-light-icon: "#3C4248"

  # ---- keyboard · dark ----
  kb-dark-background: "#0E1012"
  kb-dark-key: "#2A2D31"
  kb-dark-key-pressed: "#3C4046"
  kb-dark-key-functional: "#1E2124"
  kb-dark-key-functional-pressed: "#34383D"
  kb-dark-key-text: "#F2F4F6"
  kb-dark-key-text-functional: "#C3C9CF"
  kb-dark-accent: "#5B8CFF"
  kb-dark-on-accent: "#0E1012"
  kb-dark-divider: "#24272A"
  kb-dark-candidate-bar: "#16181A"
  kb-dark-candidate-text: "#F2F4F6"
  kb-dark-candidate-muted: "#7C838A"
  kb-dark-candidate-selected-bg: "#1D2735"
  kb-dark-candidate-selected-text: "#7FA6FF"
  kb-dark-pinyin-bg: "#2F3338"
  kb-dark-icon: "#D7DCE1"

  # ---- app (settings) · light ----
  app-light-background: "#F4F5F7"
  app-light-surface: "#FFFFFF"
  app-light-surface-container: "#F1F3F5"
  app-light-surface-container-high: "#E9ECEF"
  app-light-on-surface: "#14171A"
  app-light-on-surface-variant: "#6A7076"
  app-light-outline: "#E1E4E8"
  app-light-outline-variant: "#EDEFF2"
  app-light-accent: "#2F6BFF"
  app-light-on-accent: "#FFFFFF"
  app-light-accent-container: "#E8EFFF"

  # ---- app (settings) · dark ----
  app-dark-background: "#0E1012"
  app-dark-surface: "#17191C"
  app-dark-surface-container: "#1F2226"
  app-dark-surface-container-high: "#262A2E"
  app-dark-on-surface: "#F2F4F6"
  app-dark-on-surface-variant: "#A2A8AE"
  app-dark-outline: "#2A2E33"
  app-dark-outline-variant: "#23262A"
  app-dark-accent: "#5B8CFF"
  app-dark-on-accent: "#0E1012"
  app-dark-accent-container: "#1D2735"

typography:
  key-label:
    fontSize: 20sp
    fontWeight: 400
  key-label-function:
    fontSize: 15sp
    fontWeight: 400
  panel-title:
    fontSize: 15sp
    fontWeight: 500
  panel-body:
    fontSize: 13sp
    fontWeight: 400
  panel-label:
    fontSize: 12sp
    fontWeight: 400
  panel-micro:
    fontSize: 10sp
    fontWeight: 400
  app-display:
    fontSize: 28sp
    fontWeight: 700
  app-title:
    fontSize: 20sp
    fontWeight: 700
  app-body:
    fontSize: 15sp
    fontWeight: 400
  app-callout:
    fontSize: 14sp
    fontWeight: 400
  app-subhead:
    fontSize: 13sp
    fontWeight: 400
  app-caption:
    fontSize: 12sp
    fontWeight: 400
  app-micro:
    fontSize: 11sp
    fontWeight: 700
    letterSpacing: 0.06em

spacing:
  xs: 2px
  s: 4px
  m: 8px
  l: 12px
  xl: 16px
  xxl: 20px
  xxxl: 24px

rounded:
  kb-sm: 6px
  kb-md: 10px
  kb-lg: 14px
  app-sm: 8px
  app-md: 12px
  app-lg: 16px
  full: 9999px

sizing:
  key-height: 50px
  key-gap-h: 6px
  key-gap-v: 8px
  top-panel-height: 56px
  candidate-chip-height: 32px
  candidate-min-width: 40px
  tool-row-height: 44px
  tool-hit-size: 44px
  app-screen-padding: 20px
  app-card-padding: 16px
  app-card-gap: 12px
  app-row-min-height: 56px
  app-icon-tile: 36px

elevation:
  strategy: flat
  shadow: none
  separation: "surface color + 1px outline"

components:
  key:
    backgroundColor: "{colors.kb-light-key}"
    textColor: "{colors.kb-light-key-text}"
    rounded: "{rounded.kb-md}"
    height: "{sizing.key-height}"
  key-functional:
    backgroundColor: "{colors.kb-light-key-functional}"
    textColor: "{colors.kb-light-key-text-functional}"
    rounded: "{rounded.kb-md}"
  candidate-chip:
    textColor: "{colors.kb-light-candidate-text}"
    selectedBackgroundColor: "{colors.kb-light-candidate-selected-bg}"
    selectedTextColor: "{colors.kb-light-candidate-selected-text}"
    rounded: "{rounded.kb-sm}"
    height: "{sizing.candidate-chip-height}"
  pinyin-pill:
    backgroundColor: "{colors.kb-light-pinyin-bg}"
    textColor: "{colors.kb-light-candidate-text}"
    rounded: "{rounded.kb-sm}"
  top-panel:
    backgroundColor: "{colors.kb-light-candidate-bar}"
    height: "{sizing.top-panel-height}"
    divider: "{colors.kb-light-divider}"
  app-card:
    backgroundColor: "{colors.app-light-surface}"
    borderColor: "{colors.app-light-outline}"
    rounded: "{rounded.app-lg}"
    padding: "{sizing.app-card-padding}"
  app-list-row:
    minHeight: "{sizing.app-row-min-height}"
    titleColor: "{colors.app-light-on-surface}"
    subtitleColor: "{colors.app-light-on-surface-variant}"
  app-icon-tile:
    backgroundColor: "{colors.app-light-accent-container}"
    iconColor: "{colors.app-light-accent}"
    size: "{sizing.app-icon-tile}"
    rounded: "{rounded.app-md}"
---

# Design System: Board

**What this file is.** Board's visual identity in the open `DESIGN.md` format (Google Stitch /
`google-labs-code/design.md`). The YAML front matter holds exact tokens; the prose explains *why*.
`source_of_truth` lists the files that actually carry these values — **keep them and this file in
sync**. Tokens are normative; prose is guidance.

**Scope.** One product, two surfaces that share the same rules: the **keyboard** (a native IME drawn
at runtime by `KeyboardTheme`) and the **settings app** (Material components styled by `SettingsUi`
and `Theme.Board`). Light and dark are two sides of the same semantic roles and must change together.

## 1. Overview

Board is **quiet, flat and high-contrast**. It borrows Gboard's *usage logic* (layered keyboard, a
one-line candidate strip, language on the spacebar, long-press Backspace to delete) but removes the
visual noise: no gradients, no glows, no large fields of color, no drop shadows anywhere.

The atmosphere is **calm precision** — a tool you read at a glance. The app behind the keyboard is
the loud part; Board stays out of the way.

Three rules define everything:

1. **Black / white / grey only** — a single neutral ramp is the whole palette.
2. **One accent, used sparingly** — blue marks only the *current selection* and the *active/primary*
   state (selected candidate, active Shift, primary button, slider, icon tile). Never a background wash.
3. **Flat by default** — hierarchy comes from surface color contrast and a 1px hairline outline,
   never from shadows. (See §5.)

## 2. Colors

### Neutral scale
One ramp (`neutral-050` → `neutral-950`) underpins both palettes, so greys never drift.
`neutral-050…200` are light surfaces; `neutral-900…950` are dark surfaces.

### Accent
- **Accent** (`#2F6BFF` light / `#5B8CFF` dark): the only chromatic color. Roles: selected
  candidate, active Shift, primary button, slider thumb, focused input, icon tile glyph, status dot.
- **Accent container** (`#E8EFFF` light / `#1D2735` dark): soft tint behind a selected candidate or
  an icon tile. Always paired with accent glyph/text on top.
- **On-accent** (`#FFFFFF` light / `#0E1012` dark): text/icons on a solid accent fill.

### Keyboard palettes
- **Light**: `kb-light-background` (`#E9EBEE`) behind `kb-light-key` (`#FFFFFF`); function keys use
  a darker `kb-light-key-functional` (`#D3D7DC`) so the letter field reads as foreground;
  `kb-light-candidate-bar` (`#F7F8F9`) is the strip; `kb-light-pinyin-bg` (`#E7EAEE`) is the
  composing pill, deliberately darker than the bar so the still-composing text is obvious.
- **Dark**: every role mirrors — `kb-dark-background` (`#0E1012`), `kb-dark-key` (`#2A2D31`),
  `kb-dark-key-functional` (`#1E2124`), text `kb-dark-key-text` (`#F2F4F6`), accent brightened to
  `#5B8CFF`, `kb-dark-on-accent` dark (`#0E1012`).

### App (settings) palettes
- **Light**: page `app-light-background` (`#F4F5F7`); cards `app-light-surface` (`#FFFFFF`) with a
  hairline `app-light-outline` (`#E1E4E8`); a lower container `app-light-surface-container`
  (`#F1F3F5`) for status strips / icon tiles; text `#14171A` / `#6A7076`.
- **Dark**: page `#0E1012`; cards `app-dark-surface` (`#17191C`); outline `#2A2E33`; accent `#5B8CFF`.

### Semantic roles
| Role | Light | Dark |
|---|---|---|
| Page background | `#F4F5F7` (app) / `#E9EBEE` (kb) | `#0E1012` |
| Card / key surface | `#FFFFFF` | `#17191C` / `#2A2D31` |
| Functional key | `#D3D7DC` | `#1E2124` |
| Primary text | `#14171A` / `#111418` | `#F2F4F6` |
| Muted text | `#6A7076` / `#9AA0A6` | `#A2A8AE` / `#7C838A` |
| Accent | `#2F6BFF` | `#5B8CFF` |
| Selected candidate bg | `#E8EFFF` | `#1D2735` |
| Divider / outline | `#E1E4E8` | `#2A2E33` |

## 3. Typography

The platform font only (no custom typeface). **Weight carries hierarchy** — 700 for titles, 500 for
panel titles, 400 for everything else. No italics; bold is reserved for headings.

**Keyboard / top panel** (sp, follows system font scale):
- Key label **20sp**; function-key label **15sp** (words, not glyphs).
- Panel title **15sp** / body **13sp** / label **12sp** / micro **10sp**.
- Candidate text is **user-adjustable** (设置 → 外观 → 候选词字号): 小 16 / 标准 19 / 大 22 / 特大 25,
  each with a matched pinyin size (14/16/17/18) and English-gloss size (10/11/12/13).

**Settings app:**
- **Display 28sp / 700** — the home title.
- **Title 20sp / 700** — the section app-bar title.
- **Body 15sp / 400** — list rows, labels, switches.
- **Callout 14sp / 400** — status values.
- **Subhead 13sp / 400** — secondary values.
- **Caption 12sp / 400** — hints, row summaries.
- **Micro 11sp / 700, letter-spacing 0.06em** — card section labels ("状态", "主题"…).

## 4. Layout

A single spacing ramp — **2 / 4 / 8 / 12 / 16 / 20 / 24**. Nothing uses an off-ramp value.

**Keyboard.** 50dp key height; 6dp horizontal / 8dp vertical key gaps; 4dp / 8dp board padding.
Portrait fills the width; landscape / large screens use a floating, end-aligned card.

**Top panel.** One **fixed 56dp** band shared by every panel state (toolbar, candidates, More,
clipboard, voice, translation, phrases, expanded candidates). Internal content changes, the height
never does — so the input method never jumps when switching states. 10dp horizontal padding.
Candidate strip is one line: a compact 32dp pill/chip inside the 56dp band, 40dp min chip width, and
a 40dp action zone on the right for the `∨ / ∧` expand button.

**Settings app.** 20dp screen padding; cards separated by 12dp, cards padded 16dp; **list rows are
≥56dp** with a 36dp icon tile; section content starts after an 8dp top inset below the app bar.

## 5. Elevation & Depth

**Flat. No drop shadows anywhere** — not on keys, cards, the floating keyboard, or dialogs. Depth is
expressed only by:

1. **Surface color contrast** — a raised surface is a lighter fill (light theme) or a lighter-than-
   background fill (dark theme): key on board, card on page, icon tile on card.
2. **A 1px hairline outline** — cards use `outline`; the top panel uses a single `divider` line.

Pressed states are a *darker fill* (keys) or a Material ripple (list rows), never a shadow or a scale
animation.

## 6. Shapes

Two radius families, one per surface, plus pill — **never mix families within one surface**, and
never mix rounded and sharp corners in one view.

- **Keyboard:** `kb-sm 6` (candidate chips, pinyin pill, small pressed blocks), `kb-md 10` (keys,
  clipboard cards), `kb-lg 14` (top panel, floating keyboard card).
- **Settings app:** `app-sm 8` (inputs, icon tiles, small swatches), `app-md 12` (containers inside a
  row, icon tiles), `app-lg 16` (cards, grouped sections).
- **Pill (`full`):** Material buttons, segmented toggle groups, switches, slider thumbs.

## 7. Components

**Keyboard**
- **Keys** — 10dp rounded, 50dp tall. Letters: `kb-*-key` with `key-text`; function keys (Shift /
  Backspace / 123 / language / Enter) a darker `key-functional` fill. Pressed = darker fill, no shadow.
- **Candidate chip** — transparent; the **selected** (first) candidate gets an accent-container 6dp
  rounded background with accent text. Tap target is the full 56dp band.
- **Pinyin pill** — composing text on a `pinyin-bg` 6dp pill, clearly darker than the bar.
- **Top panel** — 14dp-rounded surface holding whichever header is active; 1px divider underneath.
- **Expanded candidate grid** — a flat 4-column grid filling the keyboard area; the first cell carries
  the selected accent background.

**Settings app**
- **App bar** — 40dp circular back button (`surface-container` circle + `on-surface` icon) + 20sp/700
  title; 16dp side padding.
- **Card** — `surface`, 16dp radius, 1px `outline`, **0 elevation**, 16dp padding.
- **Section label** — 11sp/700 micro label, `on-surface-variant`, 0.06em tracking, above a card's rows.
- **List row** — ≥56dp, ripple; optional 36dp accent-container icon tile with an accent glyph;
  title 15sp + summary 12sp muted; trailing `›` chevron.
- **Status row** — 8dp dot (accent when healthy, `outline` otherwise) + 14sp name + 14sp muted value.
- **Toggle group** — Material segmented pill; 13sp labels.
- **Switch** — Material switch, 15sp label, whole row tappable, ≥42dp tall.
- **Slider** — 15sp label + 13sp value above a Material slider; live preview while dragging.
- **Input** — outlined text field, 12dp radius, 12sp hint.
- **Buttons** — pill. **Primary** = accent fill (the single most important action in a group);
  **secondary** = outlined. Both 13sp, ~42dp tall.

## 8. Do's and Don'ts

**Do**
- Do keep to black / white / grey and treat blue as a rare accent (selection + primary action only).
- Do use the two radius families (keyboard 6/10/14, app 8/12/16) and the 2/4/8/12/16/20/24 ramp.
- Do change light and dark together, and keep `colors.xml`, `values-night/colors.xml`, `dimens.xml`,
  `themes.xml`, `KeyboardTheme.kt`, `SettingsUi.kt` and this file in sync.
- Do express depth with surface color and a 1px outline.
- Do keep the top panel at a fixed 56dp so the keyboard never jumps between states.
- Do make list rows ≥56dp with a clear title/subtitle hierarchy.

**Don't**
- Don't add gradients, glows, or drop shadows.
- Don't fill large areas with the accent color.
- Don't introduce a new corner radius or an off-ramp spacing value.
- Don't mix rounded and sharp corners in one view, or mix the two radius families in one surface.
- Don't use italics; reserve bold (700) for headings/titles.
- Don't hard-code colors/sizes in a View — read them from the theme tokens / `dimens.xml`.
