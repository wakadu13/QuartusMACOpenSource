#!/usr/bin/env python3
"""Makes the animated GIFs of the built-in guide from the frames recorded by GuideRecorder.

Usage: python3 outils/guide/make_gifs.py target/guide src/main/resources/help

Every scene folder contains PNG frames and frames.txt with one line per frame:
    file|duration in ms|x of the mouse|y of the mouse|caption
The frames are scaled to at most 900 pixels, the mouse pointer is drawn where the user clicks
and the caption is written in a band below the picture.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

MAX_WIDTH = 900
BAND = 44
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def pointer(draw, x, y, s):
    """draws an arrow pointer with its tip at x, y, and a ring showing the click"""
    r = int(14 * s)
    draw.ellipse((x - r, y - r, x + r, y + r), outline=(230, 60, 20), width=max(2, int(3 * s)))
    pts = [(0, 0), (0, 17), (4, 13), (7, 20), (10, 19), (7, 12), (12, 12)]
    k = 1.3 * s
    poly = [(x + px * k, y + py * k) for px, py in pts]
    draw.polygon(poly, fill=(255, 255, 255), outline=(0, 0, 0))


def make(scene_dir, out_file):
    lines = [l.rstrip("\n") for l in open(os.path.join(scene_dir, "frames.txt"), encoding="utf-8") if l.strip()]
    frames, durations = [], []
    width = None
    for line in lines:
        name, duration, x, y, caption = line.split("|", 4)
        im = Image.open(os.path.join(scene_dir, name)).convert("RGB")
        s = min(1.0, MAX_WIDTH / im.width)
        if width is None:
            width = int(im.width * s)
        # all frames of a scene have the width of the first one
        s = width / im.width
        im = im.resize((width, int(im.height * s)), Image.LANCZOS)
        draw = ImageDraw.Draw(im)
        if int(x) >= 0:
            pointer(draw, int(x) * s, int(y) * s, max(0.8, s))
        frame = Image.new("RGB", (im.width, im.height + BAND), (35, 45, 60))
        frame.paste(im, (0, 0))
        d = ImageDraw.Draw(frame)
        size = 17
        font = ImageFont.truetype(FONT, size)
        while d.textlength(caption, font=font) > im.width - 20 and size > 10:
            size -= 1
            font = ImageFont.truetype(FONT, size)
        d.text((10, im.height + (BAND - size) // 2 - 2), caption, font=font, fill=(255, 255, 255))
        frames.append(frame)
        durations.append(int(duration))
    # all frames get the size of the largest one
    h = max(f.height for f in frames)
    frames = [f if f.height == h else pad(f, h) for f in frames]
    pal = [f.quantize(colors=160, method=Image.MEDIANCUT, dither=Image.NONE) for f in frames]
    pal[0].save(out_file, save_all=True, append_images=pal[1:], duration=durations, loop=0, optimize=True, disposal=1)
    print(out_file, len(frames), "frames", os.path.getsize(out_file) // 1024, "KB")


def pad(f, h):
    p = Image.new("RGB", (f.width, h), (238, 238, 238))
    p.paste(f.crop((0, 0, f.width, f.height - BAND)), (0, 0))
    p.paste(f.crop((0, f.height - BAND, f.width, f.height)), (0, h - BAND))
    return p


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    for scene in sorted(os.listdir(src)):
        d = os.path.join(src, scene)
        if os.path.isfile(os.path.join(d, "frames.txt")):
            make(d, os.path.join(dst, scene + ".gif"))


if __name__ == "__main__":
    main()
