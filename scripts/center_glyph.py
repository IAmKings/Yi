#!/usr/bin/env python3
"""Post-pass for the launcher glyph:
1. recolor all opaque strokes to the icns paper color (#FFFAF0) preserving alpha,
2. center the ink bbox exactly on the 1024px canvas.

CoreText ignores NSColor foreground on some CG color-space mixes (renders
black), so the Swift renderer emits an alpha-carried monochrome glyph and this
script owns the color.
"""
import sys
import collections
from PIL import Image

PAPER = (255, 250, 240)  # icns paper color
SRC_GLYPH_COLOR = (0, 0, 0)  # verified the CoreText render is pure black only

p = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/drawable/ic_launcher_foreground.png"
im = Image.open(p).convert("RGBA")
px = im.load()
for y in range(im.height):
    for x in range(im.width):
        r, g, b, a = px[x, y]
        if a > 0:
            px[x, y] = (*PAPER, a)
bb = im.getchannel("A").getbbox()
w, h = bb[2] - bb[0], bb[3] - bb[1]
dx = int(round(512 - (bb[0] + bb[2]) / 2))
dy = int(round(512 - (bb[1] + bb[3]) / 2))
out = Image.new("RGBA", im.size, (0, 0, 0, 0))
out.paste(im, (dx, dy), im)
out.save(p)

bb2 = out.getchannel("A").getbbox()
cnt = collections.Counter()
px2 = out.load()
for y in range(0, out.height, 7):
    for x in range(0, out.width, 7):
        if px2[x, y][3] > 200:
            cnt[px2[x, y][:3]] += 1
top = cnt.most_common(1)[0][0]
assert out.getchannel("A").getextrema()[1] > 0, "empty foreground!"
assert max(w, h) <= 626, f"ink {w}x{h} exceeds 66dp safe zone"
assert top == PAPER, f"unexpected glyph color {top}"
print(f"OK ink={w}x{h} (<=626) center=({(bb2[0]+bb2[2])/2},{(bb2[1]+bb2[3])/2}) color={top}")
