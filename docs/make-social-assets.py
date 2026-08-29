#!/usr/bin/env python3
"""Regenerates the social card and the PNG app icons in web/public/.

This is a one-off asset generator, not part of the build. Nothing in the
npm scripts or the Netlify build calls it; the images it produces are
committed. Run it by hand when the dashboard's look changes:

    python3 -m pip install --user pillow
    python3 docs/make-social-assets.py

Why a script and not a hand-made image: the card has to stay in step with
the app's real colors and its real logo, and both of those live in code
(web/src/index.css and web/public/favicon.svg). Re-deriving the card from
those same values by hand every time is how a card drifts out of date.

Inputs:  docs/screenshot.png  (a full-page capture of the dashboard)
Outputs: web/public/og-image.png        1200x630 link-preview card
         web/public/apple-touch-icon.png 180x180 iOS home-screen icon
         web/public/icon-192.png         PWA manifest icon
         web/public/icon-512.png         PWA manifest icon
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SCREENSHOT = ROOT / "docs" / "screenshot.png"
PUBLIC = ROOT / "web" / "public"

# Straight out of web/src/index.css, dark theme. Keep these in step with it.
BG = (26, 31, 53)
TEXT = (167, 176, 192)
TEXT_H = (238, 241, 246)
BORDER = (54, 60, 94)
ACCENT = (109, 111, 255)
ACCENT_STRONG = (143, 145, 255)
POSITIVE = (25, 206, 179)

HELVETICA = "/System/Library/Fonts/HelveticaNeue.ttc"
REGULAR, BOLD = 0, 1


def font(size: int, index: int = REGULAR) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(HELVETICA, size, index=index)


def logo_mark(size: int, *, radius_ratio: float = 3 / 32) -> Image.Image:
    """Redraws web/public/favicon.svg at an arbitrary pixel size.

    The SVG is authored on a 32-unit grid, so every coordinate below is
    that grid scaled up. It is redrawn rather than rasterized because no
    SVG renderer is assumed to be installed, and the shapes are four
    axis-aligned rectangles plus one corner radius.
    """
    ss = 4  # supersample, then downscale, so the corners stay smooth
    px = size * ss
    u = px / 32  # one SVG unit in supersampled pixels

    # The tile's gradient runs from (6,0) to (26,32) in SVG units. Project
    # each pixel onto that axis to get its position along the ramp.
    tile = Image.new("RGB", (px, px), BG)
    x0, y0, x1, y1 = 6 * u, 0.0, 26 * u, 32 * u
    dx, dy = x1 - x0, y1 - y0
    span = dx * dx + dy * dy
    pixels = tile.load()
    for y in range(px):
        for x in range(px):
            t = ((x - x0) * dx + (y - y0) * dy) / span
            t = 0.0 if t < 0 else 1.0 if t > 1 else t
            pixels[x, y] = tuple(
                round(a + (b - a) * t) for a, b in zip(ACCENT, ACCENT_STRONG)
            )

    # Mask the tile to a rounded square, then draw the L and the rule.
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, px - 1, px - 1), radius=radius_ratio * px, fill=255
    )
    icon = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    icon.paste(tile, (0, 0), mask)

    d = ImageDraw.Draw(icon)
    d.rectangle((10 * u, 5 * u, 14 * u, 20 * u), fill=(255, 255, 255))  # L stem
    d.rectangle((10 * u, 16 * u, 22 * u, 20 * u), fill=(255, 255, 255))  # L foot
    d.rounded_rectangle(  # the teal ledger rule under the L
        (10 * u, 23 * u, 22 * u, 27 * u), radius=1 * u, fill=POSITIVE
    )
    return icon.resize((size, size), Image.LANCZOS)


def make_icons() -> None:
    # iOS ignores transparency and applies its own corner mask, so the
    # gradient goes edge to edge there. The manifest icons keep the same
    # rounded tile the browser tab shows.
    logo_mark(180, radius_ratio=0).convert("RGB").save(PUBLIC / "apple-touch-icon.png")
    for size in (192, 512):
        logo_mark(size).save(PUBLIC / f"icon-{size}.png")


def make_og_image() -> None:
    W, H = 1200, 630
    card = Image.new("RGB", (W, H), BG)

    # A soft accent glow behind the wordmark, so the top left is not a flat
    # rectangle of navy. Drawn as one radial falloff at low resolution and
    # scaled up, which is both faster and smoother than per-pixel at 1200px.
    gw, gh = 120, 63
    glow = Image.new("RGB", (gw, gh), BG)
    gp = glow.load()
    cx, cy, r = 14, 10, 62
    for y in range(gh):
        for x in range(gw):
            dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            t = max(0.0, 1 - dist / r) ** 2 * 0.30
            gp[x, y] = tuple(
                round(a + (b - a) * t) for a, b in zip(BG, ACCENT)
            )
    card.paste(glow.resize((W, H), Image.BICUBIC), (0, 0))

    # The hero: the balance-history line lifted out of the screenshot. The
    # crop sits strictly inside the chart's plot area - past the panel
    # title and border, inside the axis labels - so that scaling it to the
    # full 1200px width bleeds it off the left, right and bottom edges with
    # no seam. Those coordinates are specific to docs/screenshot.png.
    plot = Image.open(SCREENSHOT).convert("RGB").crop((830, 1006, 2448, 1300))
    plot_h = round(plot.height * W / plot.width)
    plot = plot.resize((W, plot_h), Image.LANCZOS)

    # Fade the plot's top into the background so it emerges from the card
    # instead of sitting on it as a pasted-in rectangle. The chart panel's
    # fill is a slightly lighter navy than the card, and this is what hides
    # the join.
    fade = Image.new("L", (1, plot_h))
    fp = fade.load()
    for y in range(plot_h):
        fp[0, y] = round(255 * min(1.0, y / 170))
    card.paste(plot, (0, H - plot_h), fade.resize((W, plot_h)))

    d = ImageDraw.Draw(card)

    # Wordmark: logo tile, then "Ledger" optically centered against it.
    mark = logo_mark(78)
    card.paste(mark, (72, 66), mark)
    d.text((72 + 78 + 26, 70), "Ledger", font=font(66, BOLD), fill=TEXT_H)

    # The tagline the app's own header carries, split so neither line runs
    # long enough to shrink in a chat client's preview.
    d.text((72, 178), "A double-entry core-banking ledger", font=font(34), fill=TEXT)
    d.text((72, 224), "with an append-only, idempotent transaction API.", font=font(34), fill=TEXT)

    # "Live demo" pill, mirroring the connection badge in the app header.
    label, pill_font = "LIVE DEMO", font(20, BOLD)
    tw = d.textlength(label, font=pill_font)
    pw, ph = tw + 62, 40
    px0, py0 = W - 72 - pw, 84
    d.rounded_rectangle((px0, py0, px0 + pw, py0 + ph), radius=ph / 2, outline=BORDER, width=2)
    d.ellipse((px0 + 22, py0 + ph / 2 - 5, px0 + 32, py0 + ph / 2 + 5), fill=POSITIVE)
    d.text((px0 + 44, py0 + 10), label, font=pill_font, fill=TEXT)

    card.save(PUBLIC / "og-image.png", optimize=True)


if __name__ == "__main__":
    make_icons()
    make_og_image()
    print(f"wrote og-image.png, apple-touch-icon.png and icon-*.png to {PUBLIC}")
