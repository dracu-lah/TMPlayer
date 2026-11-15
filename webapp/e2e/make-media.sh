#!/bin/bash
# The small test files the end to end tests play (4 s, 320 x 180), one per way a file plays:
# native, repacked, sound converted, decoded by libav.js (AVI and the old formats), and ones that need the app. Run again after changing a line.
set -e
cd "$(dirname "$0")/media"
SRC="-f lavfi -i testsrc2=size=320x180:rate=25:duration=4 -f lavfi -i sine=frequency=440:duration=4:sample_rate=48000"
H264="-c:v libx264 -preset veryfast -b:v 200k -g 25 -pix_fmt yuv420p"
q="-hide_banner -loglevel error -y"
out="-fflags +bitexact -map_metadata -1"
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k -movflags +faststart h264-aac.mp4
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k tail-index.mp4
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k h264-aac.mkv
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k h264-aac.ts
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a ac3 -b:a 96k h264-ac3.mkv
ffmpeg $q $SRC -map 0 -map 1 $out -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 200k -g 25 -c:a libopus -b:a 48k vp9-opus.webm
ffmpeg $q $SRC -map 0 -map 1 $out -c:v mpeg4 -b:v 200k -g 25 -c:a ac3 -b:a 96k mpeg4.avi
ffmpeg $q $SRC -map 0 -map 1 -map 1 $out -c:v mpeg4 -vtag XVID -bf 2 -b:v 200k -g 50 -c:a libmp3lame -ar 44100 -b:a 64k xvid-mp3.avi
ffmpeg $q $SRC -map 0 -map 1 $out -c:v msmpeg4 -b:v 200k -g 25 -c:a libmp3lame -ar 44100 -b:a 64k divx3.avi
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -bf 0 -c:a libmp3lame -ar 44100 -b:a 64k h264-mp3.avi
ffmpeg $q $SRC -map 0 -map 1 $out -c:v wmv2 -b:v 200k -c:a wmav2 -b:a 64k wmv2.wmv
# AVIs libav.js cannot finish: a picture it decodes but does not convert (RGB555), sound it has no decoder for
# (G.723.1), a file cut off part way.
ffmpeg $q -f lavfi -i testsrc2=size=320x180:rate=25:duration=4 $out -c:v msvideo1 msvideo1.avi
ffmpeg $q -f lavfi -i testsrc2=size=320x180:rate=25:duration=4 -f lavfi -i sine=frequency=440:duration=4:sample_rate=8000 -map 0 -map 1 $out -c:v mpeg4 -b:v 200k -g 25 -c:a g723_1 -ac 1 -b:a 6300 mpeg4-g723.avi
# The old formats chat (/chat/-1008): containers mediabunny does not open, pictures it cannot name, sound it cannot read.
OLD="-f lavfi -i testsrc2=size=320x176:rate=25:duration=4 -f lavfi -i sine=frequency=440:duration=4:sample_rate=44100 -map 0 -map 1"
ffmpeg $q $OLD $out -c:v flv -b:v 200k -g 25 -c:a libmp3lame -b:a 64k flv-mp3.flv
ffmpeg $q $OLD $out -c:v rv20 -b:v 200k -g 25 -c:a ac3 -b:a 96k rv20-ac3.rm
ffmpeg $q $OLD $out -c:v mpeg2video -b:v 300k -g 12 -c:a mp2 -b:a 128k -f vob mpeg2-mp2.vob
ffmpeg $q $OLD $out -c:v mpeg2video -b:v 300k -g 12 -c:a mp2 -b:a 128k mpeg2-mp2.ts
ffmpeg $q $OLD $out -c:v mpeg4 -b:v 200k -g 25 -c:a aac -b:a 64k mpeg4-aac.mkv
ffmpeg $q $OLD $out $H264 -c:a truehd -strict -2 h264-truehd.mkv
ffmpeg $q $OLD $out -c:v libtheora -q:v 5 -g 25 -c:a libvorbis -q:a 2 theora-vorbis.ogv
head -c 143500 xvid-mp3.avi > cut.avi
# 20 s with three chapters (Intro, The Harbour, Credits): the chapter bar, the skip pill and the autoplay tests.
printf ';FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=5000\ntitle=Intro\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=5000\nEND=15000\ntitle=The Harbour\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=15000\nEND=20000\ntitle=Credits\n' > chapters.txt
LONG="-f lavfi -i testsrc2=size=320x180:rate=25:duration=20 -f lavfi -i sine=frequency=440:duration=20:sample_rate=48000 -i chapters.txt -map 0 -map 1 -map_metadata 2"
LOWH264="-c:v libx264 -preset veryfast -b:v 60k -g 25 -pix_fmt yuv420p -c:a aac -b:a 32k"
ffmpeg $q $LONG $out $LOWH264 chapters.mkv
ffmpeg $q $LONG $out $LOWH264 -movflags +faststart chapters.mp4
rm chapters.txt
# An AVI whose header is cut off after the first 4 KiB: FFmpeg cannot read it.
head -c 4096 divx3.avi > broken.avi
ffmpeg $q -f lavfi -i testsrc2=size=160x240:rate=1 -frames:v 1 -q:v 6 $out thumb.jpg
# A video kept in a ZIP and cut into three pieces (Name.zip.001 and on, as a chat holds them): stored, so it plays out of the
# archive; and the same in a deflated one (which cannot). The MKV cut into three raw pieces is the other way a video is split.
python3 - <<'PY'
import zipfile
for name, method in (("zipped", zipfile.ZIP_STORED), ("deflated", zipfile.ZIP_DEFLATED)):
    with zipfile.ZipFile(name + ".zip", "w", method) as z:
        z.write("h264-aac.mp4", "Clip.1080p.mp4")
        z.writestr("readme.txt", "A video in a box.\n")
PY
split -b 40000 -d -a 3 --numeric-suffixes=1 zipped.zip zipped.zip.
split -b 50000 -d -a 3 --numeric-suffixes=1 h264-aac.mkv raw.mkv.
mv deflated.zip deflated.zip.001
rm zipped.zip
