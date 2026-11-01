"""Prints the "simplex" wordmark's letters as path data, for Logo.kt and the launcher banner.

Inter SemiBold, -3.5% tracking, at 100 units per em, y down, baseline at 0.
Usage: python3 tools/wordmark.py Inter-SemiBold.ttf   (needs fonttools)
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

font = TTFont(sys.argv[1])
glyphs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]
k = 100 / font["head"].unitsPerEm
x = 0.0
parts = {}
for word in ("sim", "ple"):
    pen = SVGPathPen(glyphs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    for ch in word:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, x, 0)))
        x += hmtx[name][0] * k - 3.5
    parts[word] = pen.getCommands()
print(f'SIM = "{parts["sim"]}"')
print(f'PLE = "{parts["ple"]}"')
print(f"X_AT = {x + 3:.2f}  # left edge of the x mark, 0.62 em wide and 0.56 em high on the baseline")
