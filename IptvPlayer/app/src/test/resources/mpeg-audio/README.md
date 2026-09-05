# Synthetic MPEG audio fixtures

These short test tones were generated for this project; no customer streams,
credentials or broadcast content are included. `.pcm` files are independent
FFmpeg-decoded signed little-endian 16-bit references, not JLayer output.

Reproduce using FFmpeg (the generation used 7.1, imageio-ffmpeg 0.6.0 tool bundle):

```sh
ffmpeg -f lavfi -i 'aevalsrc=0.1*sin(2*PI*440*t)|0.1*sin(2*PI*880*t):s=48000:d=0.24' -c:a mp2 -b:a 192k stereo-48k.mp2
ffmpeg -i stereo-48k.mp2 -f s16le stereo-48k.pcm
ffmpeg -f lavfi -i 'sine=frequency=660:sample_rate=44100:duration=0.25' -c:a mp2 -b:a 96k mono-44k.mp2
ffmpeg -i mono-44k.mp2 -f s16le mono-44k.pcm
ffmpeg -f lavfi -i 'aevalsrc=0.1*sin(2*PI*440*t)|0.1*sin(2*PI*880*t):s=48000:d=0.24' -c:a libmp3lame -b:a 128k -write_xing 0 -id3v2_version 0 stereo-48k.mp3
ffmpeg -i stereo-48k.mp3 -f s16le stereo-48k-mp3.pcm
```

The unit tests additionally generate Layer-I non-silent frames, Layer-II/III
silence, malformed/truncated frames, and a deliberately unsupported MPEG-2
Layer-II profile to ensure we do not falsely advertise its support.
