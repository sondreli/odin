# Handoff: Pengamine Redesign (Friendly Rounded)

## Overview

A full redesign of **Pengamine** — your Norwegian personal-finance app (formerly Odin). Covers all 5 pages: Transaksjoner, Budsjett, Lån, Kontoer, Rapporter.

The chosen direction is **Friendly Rounded** — a Monzo/Revolut-inspired warm-cream aesthetic with big rounded cards, soft shadows, pill controls, an orange accent, and Manrope as the type voice. Loud category colors are retained as the primary scanning mechanism.

## About the Design Files

The files in `prototype/` are **design references created in HTML/React (Babel-transpiled JSX)**. They are NOT production code to copy. The task is to **recreate these designs in Pengamine's existing environment**: ClojureScript + Reagent + re-frame + Tailwind, updating the relevant `*.cljs` files under `src/client/`. Apply the visual system documented below, but keep the existing re-frame event/subscription architecture, datomic backend integration, and component split (`transactions-table-component`, `treemap-component`, `period-selector-v2`, `reports`, `summed-table-component`, `chart-component`, etc.).

Open `prototype/Odin - Friendly.html` in a browser to see the chosen direction live. `Odin.html` (Bloomberg-density) and `Odin - Calm.html` (editorial calm) are alternate explorations kept for reference only — not for shipping.

## Fidelity

**High-fidelity.** All colors, typography, spacing, and interaction details below are final and should be matched precisely. Where the HTML prototype uses sample data (`prototype/data.jsx`), the existing Pengamine re-frame subscriptions (`:summed-categories`, `:period-transactions`, `:balance`, `:categories`, `:tags`, `:reports`, `:accounts`, `:loans`, etc.) supply real data — wire those up, do not invent new ones.

---

## How the visual system is structured

The prototype splits style into two layers:

1. **`styles.css`** — the structural design system: layout, tables, sidebar, components, dark+light tokens for the Bloomberg base.
2. **`friendly.css`** — an override layer that loads *after* `styles.css` and remaps tokens + key component styles into the Friendly aesthetic.

You don't need to mirror this split in production. Either:
- Translate the merged result directly into one CSS / Tailwind theme, OR
- Keep CSS variables as the source of truth (recommended for the dark/light toggle).

When in doubt, treat **`friendly.css` as authoritative**, and `styles.css` only as fallback for anything `friendly.css` doesn't override.

---

## Design tokens (Friendly Rounded, final values)

### Typography
- **Sans (UI + display):** `Manrope` 400/500/600/700/800 — Google Fonts
- **Mono:** `JetBrains Mono` 400/500/600 — only for the (optional) top ticker and small expression chips
- **Numbers** use Manrope tabular-nums by default. Heading numbers are the same Manrope at 700–800 weight (no separate serif).

### Font sizes
```
--fs-xs: 11.5px       /* status labels, small chips */
--fs-sm: 13px         /* table rows, controls */
--fs-base: 14px       /* default body */
--fs-md: 15px         /* nav, primary content */
--fs-lg: 18px
--fs-xl: 26px         /* page H2 — also overridden to 30px/800 for .page-header h2 */
```
Big numbers (`.big-mono`): **Manrope 700, 32px, letter-spacing -0.03em**. The `kr` suffix is `14px/500/var(--text-dim)`.

### Spacing & radii
- **Table row heights**: transactions table = **28px** (dense); budget + accounts tables = **52px** (comfortable).
- **Card radius**: **20px** for major panels (treemap, summary tiles, sidebar, account/loan/report cards, modals).
- **Pill controls**: **999px** on period selector, segmented, primary buttons, search input, tags, status chips, budget bars, loan history bars.
- **Inputs**: 8px radius, `var(--bg-2)` background, focus border `--c-accent`.
- **App shell**: outer container is `max-width: 1480px`, `padding: 0 32px`. The shell itself has `28px` border-radius, `20px 0` vertical margin, and a large soft drop-shadow (`var(--shadow-lg)`).

### Color — Light theme (default)
```
--bg:             #fcf6ef    /* warm cream */
--bg-1:           #ffffff    /* cards */
--bg-2:           #fcf6ef    /* nested fills */
--bg-3:           #f5ebde    /* deepest nest */
--bg-hover:       #fbf0e0
--bg-active:      #ffe8d1    /* hover/selected accents */
--border:         #f0e4d2
--border-soft:    #f7eede
--border-strong:  #e0cfb3
--text:           #2a1f15
--text-dim:       #8a7665
--text-faint:     #b8a695
--text-bright:    #1a0f08
--c-up:           #00c08a    /* income, positive deltas */
--c-down:         #ff5a5a    /* expense, negative deltas */
--c-accent:       #ff7849    /* primary action, brand square */
--c-warn:         #ffb84a
--link:           #ff7849
--highlight:      #ffe9b0
--shadow:         0 2px 12px rgba(45,30,15,0.06), 0 0 0 1px rgba(45,30,15,0.04)
--shadow-lg:      0 8px 28px rgba(45,30,15,0.10), 0 0 0 1px rgba(45,30,15,0.04)
--body-bg:        #f5ebda    /* outside the app shell */
```

### Color — Dark theme
```
--bg:             #14110d
--bg-1:           #1f1a14
--bg-2:           #261f17
--bg-3:           #2f261d
--bg-hover:       #2a2218
--bg-active:      #34281c
--border:         #2e251b
--border-soft:    #271f17
--border-strong:  #423426
--text:           #f0e4d2
--text-dim:       #a08868
--text-faint:     #6a594a
--text-bright:    #fff5e6
--c-up:           #34d399
--c-down:         #ff7a7a
--c-accent:       #ff9166    /* dimmer orange in dark */
--link:           #ff9166
--shadow:         0 2px 12px rgba(0,0,0,0.35), 0 0 0 1px rgba(255,255,255,0.04)
--shadow-lg:      0 8px 28px rgba(0,0,0,0.45), 0 0 0 1px rgba(255,255,255,0.04)
--body-bg:        #0a0805
```

Theme switching: `<html data-theme="light|dark">`, persisted in `localStorage["odin-theme"]`. Cards lose the soft drop-shadow in dark mode (set `box-shadow: none` on `.metrics-bar, .treemap-wrap, .content-main, .sidebar-panel, .budget-tile, .ribbon-cell, .budget-table-wrap, .loan-comparison, .loan-card, .account-summary, .accounts-table, .add-account-form, .rep-list, .rep-detail, .report-grid, .report-card, .modal` when `data-theme="dark"`). In dark mode, category color elements (`.cat-swatch, .cat-stripe, .tag-dot, .bar-stack > div, .budget-bar-fill, .andel-bar > div, .treemap rects`) get `filter: brightness(0.74) saturate(0.92)` to tone them down against the dark warm-brown surfaces.

### Loan-bar segment colors (same dark + light)
```
--loan-paid-p: #16a34a   /* paid principal */
--loan-paid-i: #84cc16   /* paid interest */
--loan-paid-f: #ca8a04   /* paid fees */
--loan-rem-p:  #2563eb   /* remaining principal */
--loan-rem-i:  #93c5fd   /* remaining interest */
--loan-rem-f:  #a78bfa   /* remaining fees */
```

### Category palette (KEEP LOUD)
Existing 16-hue generator in `client.services.color-service` (HSV S=0.6 V=0.9). Each category gets a fixed hex from the generated palette:
```
ting        #6e8eef
mat         #a4e65c
mat ute     #7ce69d
bil         #b56cf0
faste utg.  #ec6cc4
abonoment   #d4e65c
aktivitet   #5cb3e6
kredittlån  #5ce6c4
bolig       #ef6b5c
ferie       #f0935c
sparing     #ec6c9a
hytte       #a464e6
Lønn (inn)  #5ce67e
ukategorisert #9ca3af
```

### State attributes on `<html>`
- `data-theme="light|dark"` — full theme swap
- `data-density="dense|comfortable"` — row height (currently a no-op in Friendly since the table-specific overrides win, but keep the hook for future)
- `data-accent="vivid|muted"` — `muted` desaturates `.cat-swatch, .cat-stripe, .cat-mini, .tag-dot` via `filter: saturate(0.4)`
- `data-tm-borders="subtle|bold|none"` — treemap rect outline weight (default `none` for Friendly — see Treemap)

---

## Layout

### App shell
- Two-column grid: **nav sidebar (230px)** + **main area (1fr)**.
- Sidebar background = `var(--bg)` (cream), right-divider `1px var(--border)`.
- Shell rounded `28px` with `20px` vertical margin and `var(--shadow-lg)`. Visually looks like a card sitting on a desk.
- Outside the shell is `body { background: #f5ebda; }` (light) or `#0a0805` (dark).
- Total height: `calc(100vh - 40px)`.

### Nav sidebar
- Brand at top: 32px `#ff7849` rounded-10px square with `◆` glyph in white, beside `odin` (or `pengamine` once renamed) in Manrope 800/18px lowercase.
- 5 nav items as full-width pill buttons, `12px 14px` padding, `12px` radius, `15px/600` text, `12px` icon→label gap.
- Active item: `background: #ffffff` (light) / `var(--bg-active)` (dark), text bright, `font-weight: 700`, subtle shadow `0 1px 0 rgba(0,0,0,0.04), 0 1px 3px rgba(0,0,0,0.05)` in light mode.
- Bottom: theme toggle button (sun/moon icon + label, same pill style as nav items) + user card (avatar = first letter of email in `--c-accent` background, status dot animating).
- **No top ticker by default.** The ticker exists in `app.jsx` but is hidden behind the `showTicker` tweak (defaults to `false`).

---

## Page: Transaksjoner

Layout (top to bottom inside scroll, `32px 40px` padding):
1. **Metrics bar** — rounded card with status tiles: Saldo, Inn, Ut, Netto, Sparerate. Right side has "Vis budsjett" / "Vis filtre" checkboxes.
2. **Treemap panel** — squarified treemap of expense categories (existing layout algo in `treemap-component/layout.cljs`).
   - Each rect rendered as a rounded 8px solid color, **1px margin** between rects (no outline border), labels lowercase white with `text-shadow: 0 1px 3px rgba(0,0,0,0.55)`.
   - Hover fades other rects to 0.35 opacity (120ms transition).
   - Click selects/deselects a category and applies the row filter.
   - Footer: `▲ 22 681 brukt` mono spending arrow (label still uses tabular Manrope, not separate mono font — kept the className for compatibility).
3. **Controls row** — Segmented(Tabell|Stolpe|Sum), PeriodSelector, search input, multi-select toggle.
4. **Filter path** — `All › mat` breadcrumb with category swatch; right-aligned summary count.
5. **Split content** — `1fr 280px` grid: chosen view (table / stacked bar / summed) on the left; categories+tags sidebar on the right.

### Transactions table
| Col | Width | Content |
|-----|-------|---------|
| Endre/checkbox | 36px | `Endre` link or selection checkbox |
| Category square | 24px (centered) | **16×16 rounded square in category color, 5px radius**, centered in cell |
| Beløp | auto | right-aligned, `--c-up` if >0 else inherit |
| Dato | auto | right-aligned, dim, `D.M.YYYY` |
| Beskrivelse | flex | text + inline tag dots + filter mark `●` |
| (no category pill on the right) | — | removed in Friendly |
| View | 36px | link, only shown when row was matched by a filter |

**Row height: 28px** (dense, only this table). Bottom border `--border-soft` per row, hover bg `--bg-hover`. Header padding `12 18`, body padding `4 18`.

Sort headers: clickable, sigil `▲▼△` at 0.5 opacity to the right of the label.

Inline edit panel (full-width colspan when expanded): Lagre primary-xs · Kategori select · Filter text input · Tags chip row. Highlight description matches with `<mark class="hl">` (background `var(--highlight)`).

Multi-select bar (above table when items selected): count, signed sum, "Tildel kategori" primary-xs, "Avbryt" ghost-xs.

### Categories + Tags sidebar
- Card with **22 20** padding, white bg, rounded 20px, soft shadow.
- Section title (`Kategorier`, `Tags`): `12.5px/700`, no uppercase letter-spacing, with right-aligned count pill (`--bg-2` bg, 999px radius, `1 8` padding).
- Category rows: `Endre` link (38px) · name with **14×14 rounded-5px swatch** + bucket pill (right) · transaction count (right-aligned 10px faint).
- Expanded category: filter-line inputs ("REMA", "BUNNPRIS", …) + Lagre/Slett.
- "+ Ny kategori" link → inline form (name + 16-color palette picker, swatches 22×22 with 3px radius).
- Tags list: dot + name + count + × remove.

### Stolpediagram view
- Daily stacked bars, expenses only.
- Y-axis on right with 3 ticks (max, max/2, 0).
- Stack segments sorted desc by value within column.
- 90% column width.

### Summed view
- Table: 16×16 colored square · category chip · count · snitt · sum · andel-bar (full-width inside cell, color = category, width = share of total).

---

## Page: Budsjett

1. **Page header** with PeriodSelector.
2. **Summary grid** (4 tiles in a row, gap 14px):
   - Inntekt, Brukt, Disponibelt, Igjen i budsjett — each in a Manrope-700 32px display number with `kr` suffix.
3. **Ribbon (50/30/20)** — Behov / Ønsker / Bør side-by-side cards (`flex: 1` each, 12px gap between cards, each is its own pillow `20 22` padding white bg rounded 20px). Header: name + `XX% / YY% mål` mono. 4px progress fill in `--c-up` if ≤ target else `--c-down`. Footer: `1 234 / 5 678`.
4. **Budget detail table** — categories grouped by bucket. Each bucket starts with a **bucket-header row** (Behov / Ønsker / Bør, right-aligned `pct% · spent/target`). Bucket sum rows have been **removed** (the header row carries the totals). Final "Resultat" total row.

### Budget bar (in-row progress)
- **10px** tall, fully rounded (999px) track and fill.
- Track `--bg-3`, fill = category color, width = `spent / max(spent, target)`.
- If overspent: diagonal stripe pattern `repeating-linear-gradient(45deg, ${color} 0 4px, var(--c-down) 4px 8px)` for the overspent slice.
- Vertical 1.5px `--text-bright` mark at the target position.

### Edit panel (per category row)
- Mål per måned input (right-aligned, 80px)
- Bøtte select (Behov/Ønsker/Bør)
- Rollover ubrukt checkbox
- Lagre primary-xs · Slett ghost-xs

### Budget table — category color squares
- 16×16 rounded-5px square in real category color, centered in the cat-stripe column. Same visual language as the transactions table.

---

## Page: Lån

1. **Page header** with "+ Nytt lån" primary-pill button.
2. **Summary grid** (4 tiles, gap 14px): Total gjeld (neg), Månedlig betaling, Betalt totalt (pos), Snittrente.
3. **Sammenligning panel** — legend strip + proportional horizontal segmented bars per loan (paid p/i/f + remaining p/i/f). Largest loan = 100% width.
4. **Loan cards** — collapsible. Header row shows name + provider on the left and 5 inline stat blocks (Rest, Rente, Termin, Ferdig om, Nedbetalt).
5. **Expanded loan card** opens to:
   - 4-col detail grid (Opprinnelig, Total kostnad, Total rente, Måneder igjen).
   - **Tab control (pill segmented)** with three tabs: **Plan**, **Fordeling**, **Historikk**.
     - **Plan** — schedule of next 12 months (Måned · Avdrag · Rente · Termin · Rest gjeld). Month labels are real (`Jun 26`, `Jul 26`, …), not `+1 mnd`. Followed by the "Hva-om kalkulator" strip (extra monthly payment → interest saved + months saved).
     - **Fordeling** — full-width segmented paid/remaining bar (height 36px, rounded 14px) + grid of per-segment cards (each: 4px color stripe + label + amount + %) + a summary block (Betalt så langt / Gjenstår / Total kostnad).
     - **Historikk** — last 12 months actual payments table with a mini horizontal **Fordeling** bar per row (rounded 999px, paid-principal in `--loan-paid-p` and interest in `--loan-paid-i`, widths proportional to the largest month's termin).

### Tab visual (Friendly override)
- Inline-flex on `--bg-2` pill bg, 4px padding, 999px radius.
- Each tab `7 18 6` padding, 600 weight.
- Active tab: white bg, `--text-bright`, `0 1px 3px rgba(0,0,0,0.06)` shadow.

---

## Page: Kontoer

1. Page header with "+ Koble til ny bank" primary-pill.
2. **Total saldo card** — big-mono number + last-sync timestamp.
3. **Accounts table** — Konto (icon + name), Bank, Kontonummer (mono dim), Saldo (right-aligned `med`), Sist sync (mono small dim), settings + "Fjern" link (red).
4. **Add-bank form** (slides in below): 4-step ordered list with developer.sparebank1.no link; callback URI in a copy-row code box with "Kopier/Kopiert!" button; Client ID / Client Secret inputs; Koble til (primary) + Avbryt (ghost).
5. **Confirm-delete modal** — overlay (rgba 0 0 0 / 0.5 + 2px backdrop blur) + dialog rounded 20px, two action buttons.

---

## Page: Rapporter

**Single picker — no sidebar list.** Two states:

### Gallery (default)
- Page header with PeriodSelector + "+ Ny rapport" primary-pill (right-aligned).
- Grid of cards (auto-fill, minmax 320px, gap 14px). Each card:
  - Header: report name + expression (mono dim, truncated)
  - Mini chart (`120px` tall) using the report's chart type
  - Stats row at the bottom: **Snitt/mnd** (signed, colored) + **Sum** (signed)
- Last card: **"+ Ny rapport"** — dashed 2px border, centered icon + label, transparent bg.

### Detail (click a card)
- Page header replaced with a "← Tilbake til rapporter" link.
- Single full-width card containing:
  - Header: report name + period label, right side: Stolpe/Fossefall segmented + PeriodSelector.
  - Expression builder: full-width input + chip rail of variables (10 most-relevant) + operators.
  - Big chart (`300px`).
  - Stats grid (Sum / Snitt / Min / Max) — 4-col.
  - Footer actions: Lagre endringer (primary) · Dupliser (ghost) · Slett rapport (right-aligned red link).

The "Alle rapporter" grid at the bottom (which used to be the second picker) **has been removed.**

---

## Shared primitives

Build these as Reagent components in `src/client/components/ui/` (new folder).

### Buttons (all 999px radius in Friendly)
- `.btn-primary` — bg `--c-accent`, color `#fff`, `11 22` padding, 700 weight, soft shadow `0 2px 8px rgba(255,120,73,0.25)`.
- `.btn-ghost` — bg `--bg-2`, no border, `11 22` padding, 600 weight.
- `.btn-primary-xs` — bg `--text-bright`, color `--bg-1`, `5 14` padding, 600 weight.
- `.btn-ghost-xs` — bg `--bg-2`, no border, `5 14` padding, 600 weight.
- `.btn-danger` — bg `--c-down`, white text.

### Segmented (`Segmented`)
Pill: `--bg-2` bg, 4px padding, 999px radius, no internal borders. Active button: `--bg-1` bg + shadow `0 1px 3px rgba(0,0,0,0.06)`.

### Period selector (`PeriodSelector`)
Outer pill `--bg-2` 4px padding 999px radius. Year cell on white bg pill. Months: 999px radius pills, active = `--c-accent` bg + white. Måned/Kvartal/År type pills on the right, separated by a thin left border.

### Status tile (`StatusTile`)
Label `11.5/600/--text-dim` (no caps in Friendly). Value Manrope 600 in `--text-bright` (or accent for income/expense). Optional sub line with delta (`▲/▼` + %).

### Treemap (`Treemap`)
- Squarified algorithm (keep `treemap-component/layout.cljs` as-is).
- Rects: 8px border-radius, 1px margin (no outline border).
- Labels: lowercase, 600 weight, `text-shadow: 0 1px 3px rgba(0,0,0,0.55)`, white.
- Hover dims other rects to 0.35 (120ms).
- Optional `onClick(item)`, `onHover(id)`, `hoveredId`, `selectedName`.

### Tag chip
- 999px radius, `4 12` padding, 600 weight, 11px font.
- Unselected: bg `--bg-2`, `--text-dim`.
- Selected: bg = tag color, `rgba(0,0,0,0.85)` text.

### Inputs (`txn-input`, `form-input`, `be-input`, `sb-input`, `rep-expr-input`, `sb-filter-input`)
- Bg `--bg-2`, transparent border, 8px radius, `8 12` padding.
- Focus: bg `--bg-1`, border `--c-accent`, no default outline.

### Search input (`search-wrap`)
- Pill: `--bg-2` bg, no border, 999px radius, `8 14` padding, icon left.

### Scrollbar (webkit)
- 8px width, transparent track, thumb `--border` rounded 999px.

---

## Interactions

- **Theme toggle** — bottom of nav, persists to `localStorage["odin-theme"]`.
- **Keyboard 1–5** still routes to pages when no input has focus (carried over from V1; visible kbd hints in the sidebar were removed but the bindings remain).
- **Treemap rect click** — toggles category filter (re-frame `:filter-path`).
- **Multi-select toggle** in the table → hides single-row Endre links, shows checkbox column + bulk-edit bar.
- **Sort headers** — first click sets `desc`, second toggles.
- **Search input** — filters by description or category name; matches highlighted with `<mark class="hl">`.
- **Live status dot** in the nav user card: 2s pulse animation (`live-pulse` keyframes).

---

## Tweaks (optional in-app live controls)

The prototype exposes a Tweaks panel for live-controlling the visual system. In production, hide this behind a `?dev=1` URL or a settings menu — or skip it entirely. Available tweaks:

| Tweak | Type | Range | Effect |
|-------|------|-------|--------|
| Tetthet | radio | Tett / Lett | `<html data-density>` |
| Ticker øverst | toggle | on/off | shows the top ticker |
| Treemap høyde | slider | 160–420px | treemap container height |
| Kategorifarger | radio | Knallfarger / Dempet | `<html data-accent>` saturation |
| Treemap ramme | radio | Subtil / Tydelig / Ingen | `<html data-tm-borders>` |
| Monospace tall | toggle | on/off | reserved hook (no-op in Friendly) |

---

## Data shape mapping

| Prototype field | re-frame source |
|-----------------|-----------------|
| Categories list | `:categories` |
| Per-period totals | `:summed-categories` |
| Transactions list | `:displayed-transactions-data` |
| Tags | `:tags` |
| Account balance | `:balance` (`:available-balance`) |
| Accounts list | `:accounts` |
| Loans list | `:loans` — apply `loan-svc/loan-summary` for the segmented bar values |
| Reports list | `:reports` |
| Period | `:period`, `:reports-period` |
| Filter path | `:filter-path` |
| Builder category | `:builder-category` |
| Multi-select | `:multi-select` |

Reuse existing events (`:edit-category3`, `:store-category3`, `:mark-transaction`, `:add-filter`, `:toggle-multi-select-mode`, `:multi-select-edit`, `:multi-select-save`, `:set-active-menu`, `:set-period-transactions`, `:store-report`, `:connect-account`, `:delete-account`, etc.) as-is. The redesign changes presentation, not actions.

---

## Norwegian copy (match exactly)

- Nav: Transaksjoner, Budsjett, Lån, Kontoer, Rapporter
- Buckets: Behov, Ønsker, Bør
- Period types: Måned, Kvartal, År
- Months: Jan Feb Mar Apr Mai Jun Jul Aug Sep Okt Nov Des
- Actions: Endre, Lukk, Lagre, Slett, Avbryt, Fjern, Kopier, Kopiert!, Koble til, Ny kategori, Ny tag, Ny rapport, Tilbake til rapporter, Lagre endringer, Dupliser, Slett rapport
- Status labels: Konto, Saldo, Inn, Ut, Netto, Sparerate, Gjeld
- Loan tabs: Plan, Fordeling, Historikk
- Loan stats: Rest, Rente, Termin, Ferdig om, Nedbetalt, Sammenligning, Plan neste 12 måneder, Hva-om kalkulator, Betalt avdrag, Betalt rente, Betalte gebyr, Gjenst. avdrag, Gjenst. rente, Gjenst. gebyr
- Search placeholder: "Søk i transaksjoner…"
- Loading banner: "Henter transaksjoner fra banken… Dette kan ta opptil et minutt."

---

## Files in `prototype/`

| File | Role |
|------|------|
| `Odin - Friendly.html` | **Entry — open this in a browser** to view the chosen direction |
| `styles.css` | Base design system (loaded first) |
| `friendly.css` | Friendly Rounded overrides (loaded after styles.css — authoritative) |
| `app.jsx` | App shell: nav, top ticker (hidden), routing, theme + tweaks state |
| `shell.jsx` | Primitives: `Icon`, `Sparkline`, `Segmented`, `StatusTile`, `PeriodSelector`, format helpers, `useTheme` |
| `treemap.jsx` | Squarified treemap renderer |
| `transaksjoner.jsx` | Transaksjoner page |
| `budsjett.jsx` | Budsjett page |
| `lan.jsx` | Lån page (tabbed loan cards) |
| `kontoer.jsx` | Kontoer page |
| `rapporter.jsx` | Rapporter page (gallery + detail) |
| `data.jsx` | Sample data — for shape reference only |
| `tweaks-panel.jsx` | Tweaks scaffold (host-only, skip in production) |

Also included (alternate explorations, **not for shipping** — kept only as design context):
- `Odin.html` (V1 — Bloomberg-density)
- `Odin - Calm.html` (V2 — Calm editorial)
- `calm.css`

If anything in this README is ambiguous, **fall back to `prototype/Odin - Friendly.html` as the source of truth**.

---

## Assets

No proprietary brand assets. Icons are inline SVG (lucide-style, stroke 1.5, 14–16px) drawn in `shell.jsx`'s `Icon` component. You can use the same set or substitute your existing icon system.

Fonts: load Manrope + JetBrains Mono from Google Fonts. If self-hosting, woff2 files are on Google Fonts CDN or rsms.me (mono).

---

## Migration notes

- **Tailwind**: the prototype uses raw CSS variables. Either translate tokens into `tailwind.config.js > theme.extend`, or keep them as CSS variables and import alongside `tw/style.css`. **CSS variables are recommended** because the dark/light toggle becomes a one-attribute change.
- **Reagent vs React**: JSX in `prototype/` uses `useState/useEffect/useMemo`. When porting, prefer Reagent's `r/atom` + `with-let` (already idiomatic in the codebase). The existing `views.cljs` patterns are a good model.
- **D3**: the existing `chart-component` uses D3 for the stacked bar chart. The bar chart in `transaksjoner.jsx` is a CSS flexbox version — either is fine; the styling rules are the same.
- **Project name**: the prototype still says "ODIN" in the sidebar brand. Update to "pengamine" wherever it appears (brand mark + page title `<title>`).
