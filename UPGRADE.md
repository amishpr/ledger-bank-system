# UI upgrade: review and plan

This is a review of the Ledger dashboard UI (`web/`) and the plan for the
redesign on the `ui-upgrade` branch. It covers what was measured, what is
wrong, what the new design looks like, and the order the work gets done in.

## How the review was done

- Ran the demo build (`VITE_DEMO=true`) and captured it with Playwright 1.48
  at 1440px (dark and light) and 390px (dark), full page.
- Checked WCAG contrast for every text/background token pair with a small
  script, and counted em dashes, horizontal overflow and small hit targets
  in the rendered DOM.
- Read every component in `web/src/components`, `App.tsx`, `App.css` and
  `index.css`.

Skills applied: `redesign-existing-projects` (audit, then targeted fixes on
the existing stack) and the parts of `design-taste-frontend` that apply to
product UI (no em dashes, one accent, one radius scale, no eyebrow labels
everywhere, contrast checks, reduced motion). The taste skill says it is not
written for dashboards or data tables, so its landing page rules (hero,
imagery, bento, marquees) are not used here.

**Design read:** a single-screen banking operations dashboard, built as a
portfolio piece for engineers and recruiters. It should feel calm and
precise, like a real ledger, not like a costume of a trading terminal.
Native CSS tokens, Geist and Geist Mono, restrained motion.

**Dials:** variance 3 (a dashboard needs a steady grid), motion 3 (feedback
only), density 7 (dense, but readable at arm's length).

**Mode:** redesign, preserve. The logo, the navy dark theme, the indigo
brand hue, the teal up and magenta down meanings, dark as the default
theme, the page's information and all behavior stay. The visual language
around them gets tightened.

## Findings

### 1. Accessibility (highest priority)

Only body text passes AA today. Everything else is small text (9 to 13px),
so the large-text allowance does not apply.

| Pair | Dark | Light |
| --- | --- | --- |
| Muted text (labels, headings, dates, hints) | 2.61 fail | 3.83 fail |
| Negative amounts | 2.96 fail | 3.84 fail |
| Positive amounts | ok | **1.66 fail** |
| Accent links (Reverse, Export CSV) | 3.59 fail | 3.42 fail |
| White label on accent button | 3.91 fail | 3.42 fail |

- 46 buttons, links and inputs are under 24px in one dimension (the
  `Reverse` links, chart tabs, header pills).
- Toasts are clickable `div`s: not reachable by keyboard and not announced
  to screen readers (no live region).
- No `prefers-reduced-motion` handling for the balance tween, row flash,
  toast or feed animations.
- The selected account has no `aria-current`/`aria-pressed`. The spending
  chart tabs have no tab semantics.
- Several buttons have no visible focus style of their own.

### 2. Layout and hierarchy

- **The dashboard starts below the fold.** The About panel and the demo
  banner are two full-width boxes that take about 250px before any
  number appears. On a phone the first account row is roughly 830px down.
- **Four equal stat tiles** give the same weight to "Net position" and to
  "Total liabilities $0.00" and "Accounts 12".
- **Cards inside cards.** Every account is a bordered card with a colored
  left rule, inside a bordered panel. Twelve of them make a long, noisy
  column.
- **The selected account's story is split up.** Its statement is top right
  and its balance chart is below the fold, separated by a whole row.
- **The transfer form exists twice.** "Recurring transfers" contains a full
  second copy of From, To, Amount and Description.
- **The new account form floats** between panels with no heading or frame.
- **Selects are truncated** ("Checking - Ale") in the 320px column.
- **An empty "Live activity" box** stretches to fill leftover height.

### 3. Color

- About 12 hues on screen: indigo accent, teal, magenta, amber, five
  account-type colors and five chart category colors. Type color is shown
  three times per account (icon, badge, text).
- The accent differs between themes (indigo in dark, sky blue in light).
- The indigo is fully saturated (`#6d6fff`), which reads as the generic
  "AI purple" look. It is the brand hue, so it stays, but desaturated and
  used only for things you can click.
- The spending bars use five colors when the labels already name each bar.
  Color adds nothing there.

### 4. Typography

- System font plus `ui-monospace` for nearly everything: headings, labels,
  buttons, badges, the tagline. The mono everywhere is what makes it read
  as a costume.
- Every panel title, label, badge, tab and button is uppercase with wide
  tracking. There are more than 30 of these labels on the page.
- Sizes of 9 and 10px on badges and labels are too small to read.

### 5. Components

- The statement shows direction three times: a DEBIT/CREDIT badge, a
  +/- sign and a color. It shows a "Reverse" link in accent color on every
  row, 40 identical links competing with the data.
- Dates use the full `toLocaleString()` ("9/25/2026, 2:16:00 PM") with
  seconds, which is the widest column and wraps to three lines on a phone.
- Balance chart y-axis reads `$15,000.00`, and its footnote describes
  implementation details.
- Category and account names repeat their group ("Expenses - Rent" under
  "Expenses").
- The theme button label shows the theme you would switch to, which reads
  like the current state.
- Icons are hand drawn SVG paths.

### 6. States

- No loading state. The page renders empty panels until data arrives.
- Empty states are single grey lines of text.
- Errors are fine inline, but the toast-only failures (pause, cancel) have
  no persistent trace.

### 7. Mobile (390px)

- Header buttons wrap under the logo.
- The statement table scrolls sideways and the Balance column is cut off.
- 3,500px of page, mostly from the account cards and duplicated forms.

### 8. Copy

- Two visible em dashes in panel titles ("Statement — Checking - Alex"),
  which also clash with the hyphen inside account names.

## Target design

### Tokens

One accent, used only for interactive things. Teal and magenta only mean
up and down. Amber only means "look at this". All pairs below were
measured and pass AA.

| Token | Dark | Light |
| --- | --- | --- |
| `--bg` | `#1a1f35` (brand navy, kept) | `#f3f4f8` |
| `--surface` | `#20263f` | `#fcfcfd` |
| `--muted` | `#8f97ab` (5.09) | `#5f6678` (5.60) |
| `--accent` text | `#9597f0` (5.65) | `#4a4cc9` (6.46) |
| `--accent-solid` button | `#5d60e0` (4.83) | `#4f52d6` (5.80) |
| `--positive` | `#19ceb3` (7.46) | `#0e7c6b` (4.98) |
| `--negative` | `#f0609a` (4.85) | `#c2255f` (5.50) |
| `--warning` | `#f2b400` (8.01) | `#9a5b0c` (5.28) |

Radius: 4px on controls, 8px on sections. No shadows except toasts.

### Type

Geist (UI) and Geist Mono (money, dates, IDs), self-hosted through
`@fontsource-variable`. Base 14px. Section titles in sentence case, 15px
semibold. No uppercase labels except the table header row.

### Layout (desktop)

```
+----------------------------------------------------------------+
| [L] Ledger   short tagline                  Live  Source  (◐)  |
+----------------------------------------------------------------+
| About (2 short paragraphs, 65ch)      | Demo mode note + Reset |
+----------------------------------------------------------------+
| Net position $42,966.96   Assets ...   Liabilities ...   12 acc |
+-------------------+--------------------------------------------+
| Accounts          | Checking - Alex              $9,663.25     |
|  Assets   $43k    | Asset account                Export CSV    |
|   Checking  ...   | [ balance chart ]                          |
|   Savings   ...   | Date | Description | Debit | Credit | Bal  |
|  Equity           | ...                                        |
|  Revenue          |                                            |
|  Expenses         |                                            |
|  + New account    |                                            |
+-------------------+----------------------+---------------------+
| Move money        | Spending             | Live activity       |
| From / To         | (single-hue bars)    |                     |
| Amount / Repeat   +----------------------+---------------------+
| Description       | Scheduled transfers                        |
| [Post transfer]   |                                            |
+-------------------+--------------------------------------------+
```

- **Accounts rail:** grouped by type with a subtotal per group, one row per
  account (name left, balance right, mono). No badges, no per-type colors.
  The selected row gets the accent bar and `aria-current`. The new
  account form lives at the bottom of the rail.
- **Account detail:** name, type and balance as the header, then the
  chart, then the statement. One place for everything about the selected
  account.
- **Statement:** a classic ledger layout, Date / Description / Debit /
  Credit / Balance. The amount sits in its own column, colored by whether
  it raised or lowered this account (the existing type-aware rule) and
  keeps its sign so color is never the only signal. Short dates. "Reverse"
  becomes an icon button that shows on row hover or focus, and is always
  visible on touch screens.
- **Move money:** one form. A "Repeat" select (Just once, Every minute,
  Daily, Weekly, Monthly) decides whether it posts a transaction or creates
  a schedule. The button label follows the choice.
- **Scheduled transfers:** the list only, as compact rows.
- **Spending:** single-hue bars without filled tracks, labels without the
  "Expenses - " prefix, proper tab semantics.
- **Summary band:** net position as the lead number, the rest smaller, no
  tiles.

### Mobile

A single column in reading order: summary, accounts, account detail, move
money, spending, activity, schedules. Icon-only header actions. The
statement drops to Date / Description / Amount / Balance on narrow screens
so nothing is cut off.

### Interaction and states

- Skeleton rows while the first load is in flight.
- Written empty states that say what to do next.
- Toasts become buttons inside an `aria-live` region.
- `:focus-visible` ring on every control, `:active` press feedback,
  150ms transitions.
- Reduced motion turns off the tween, the flash and the entry animations.

### Kept on purpose

- Dark theme is the default, as chosen in an earlier commit. System
  preference is still not consulted. Easy to change later in `useTheme.ts`.
- Logo and favicon unchanged.
- SEO tags, the demo backend and the API contract untouched. The only
  `index.html` change is the light `theme-color`, updated to the new
  light background.

## Execution plan (kept token-lean)

The goal is the fewest edit and verify cycles.

1. **Fonts and icons.** Install `@fontsource-variable/geist`,
   `@fontsource-variable/geist-mono` and `@phosphor-icons/react` in `web/`.
   Import the fonts once in `main.tsx`.
2. **Tokens.** Rewrite `index.css` in one pass (both themes, type scale,
   focus ring, reduced motion).
3. **Styles.** Rewrite `App.css` in one pass for the new layout. It is
   mostly new rules, so a full rewrite is cheaper than dozens of edits.
4. **Components,** in dependency order, each rewritten whole:
   `AccountsPanel` (grouped rail, hosts `NewAccountForm`), `AccountDetail`
   (new, wraps chart and statement), `StatementPanel` renamed to
   `StatementTable`, `BalanceHistoryChart` (restyle), `TransferForm`
   renamed to `MoveMoneyForm` (absorbs the recurring create form),
   `RecurringTransfersPanel` (list only), `SpendingChart`, `ActivityFeed`,
   `StatsBar` renamed to `SummaryBand`, `ToastStack`, `RepoLink`,
   `ThemeToggle`, `AboutPanel` and `DemoBanner` (restyled and placed side
   by side, no new wrapper component). Renames use `git mv` so history
   follows. `AccountTypeIcon` is deleted.
5. **App shell.** Update `App.tsx` for the new grid and a `loaded` flag for
   skeletons.
6. **Verify once, then fix.** `tsc -b`, `oxlint`, `vitest`, a production
   build, then one Playwright 1.48 pass (1440 dark, 1440 light, 390 dark)
   with the same DOM audit (em dashes, overflow, small targets, console
   errors). Then a scripted smoke test: select an account, post a
   transfer, schedule one, reverse a row, switch tabs and theme.
