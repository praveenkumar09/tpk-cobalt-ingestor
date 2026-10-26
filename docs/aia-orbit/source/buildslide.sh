#!/bin/zsh
set -e
H=${0:A:h}; OUT=$1
B64="data:image/png;base64,$(base64 -i $H/orbit-face.png | tr -d '\n')"
python3 - "$H/orbit-slide.html" "${TMPDIR:-/tmp}/orbit-slide.html" "$B64" <<'P'
import sys;s=open(sys.argv[1]).read().replace('__FACE__',sys.argv[3]);open(sys.argv[2],'w').write(s)
P
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; T=${TMPDIR:-/tmp}/orbit-slide.html
"$C" --headless=new --disable-gpu --no-pdf-header-footer --print-to-pdf=$OUT/EXCO-One-Slide-AIA-Orbit.pdf file://$T 2>/dev/null
"$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=1920,1080 --screenshot=$OUT/EXCO-One-Slide-AIA-Orbit.png file://$T 2>/dev/null
