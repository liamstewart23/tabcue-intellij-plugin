#!/usr/bin/env bash
# Scales each capture to fit 1280x800 and pads it onto that canvas, so nothing is stretched.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p store

# The New UI dark background, so the padding reads as IDE chrome rather than as a border.
PAD=1E1F22
W=1280
H=800

for src in [0-9][0-9]-*.png; do
    out="store/$src"
    cp "$src" "$out"

    width=$(sips -g pixelWidth "$out" | awk -F': ' '/pixelWidth/{print $2}')
    height=$(sips -g pixelHeight "$out" | awk -F': ' '/pixelHeight/{print $2}')

    # Only ever downscale, and only on the dimension that is actually over budget, so a capture
    # already inside the canvas keeps its native pixels.
    if [ "$width" -gt "$W" ] || [ "$height" -gt "$H" ]; then
        if [ $((width * H)) -gt $((height * W)) ]; then
            sips --resampleWidth "$W" "$out" >/dev/null
        else
            sips --resampleHeight "$H" "$out" >/dev/null
        fi
    fi

    sips --padToHeightWidth "$H" "$W" --padColor "$PAD" "$out" >/dev/null
    echo "$out -> $(sips -g pixelWidth -g pixelHeight "$out" | awk -F': ' '/pixel/{printf "%s ", $2}')"
done
