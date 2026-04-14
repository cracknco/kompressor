# Fixture Sources

Exhaustive catalog of datasets for the Kompressor test fixture bank.
Each entry includes licensing, suitability for our codec/container coverage matrix,
and notes on which test categories (happy-path, edge-case, adversarial, HDR, etc.)
the source serves.

> **Selection criteria**: freely redistributable OR downloadable-on-demand with
> stable URLs; covers at least one gap in our codec/container/edge-case matrix;
> files are real-world captures or industry-standard references (not synthetic).

---

## Video

### 1. AVT-VQDB-UHD-1
- **URL**: <https://telecommunication-telemedia-assessment.github.io/AVT-VQDB-UHD-1/videos.html>
- **License**: CC BY 4.0
- **Formats**: H.264, H.265 (multiple bitrates, UHD)
- **Suitability**: Quality benchmarking with MOS labels. Excellent for bitrate-ladder validation and HEVC Main/Main10 coverage. Large files (UHD) — R2 storage.
- **Categories**: `happy-path/video`, `high-resolution/video`

### 2. Netflix Open Content
- **URL**: <https://opencontent.netflix.com/>
- **License**: CC BY-NC-ND 4.0 (non-commercial OK for testing)
- **Formats**: H.264, HEVC, HDR10, Dolby Vision (El Fuente, Meridian, Chimera, Sparks)
- **Suitability**: HDR10, HDR10+, Dolby Vision Profile 5/8 coverage. El Fuente provides short clips ideal for CI. Meridian covers wide gamut BT.2020.
- **Categories**: `hdr/video`, `happy-path/video`

### 3. Xiph.org derf's Test Media Collection
- **URL**: <https://media.xiph.org/video/derf/>
- **License**: Various (most are royalty-free research sequences)
- **Formats**: Y4M (raw), can be transcoded to any target codec
- **Suitability**: SDR reference sequences (crowd_run, ducks_take_off, park_joy, old_town_cross). High motion + detail — stress tests for encoder quality. Small Y4M clips available.
- **Categories**: `happy-path/video`, `edge-cases/video`

### 4. Big Buck Bunny / Tears of Steel / Sintel
- **URL**: <https://peach.blender.org/>, <https://mango.blender.org/>, <https://durian.blender.org/>
- **License**: CC BY 3.0
- **Formats**: MP4 (H.264), WebM (VP9), MKV, various resolutions (480p-4K)
- **Suitability**: Universally available, stable URLs. Good for happy-path H.264/VP9 and container diversity (MP4, WebM, MKV). Short clips extractable via ffmpeg.
- **Categories**: `happy-path/video`, `legacy-codecs/video`

### 5. Blender Cloud Open Movies
- **URL**: <https://cloud.blender.org/open-projects>
- **License**: CC BY 4.0 / CC BY-SA 4.0
- **Formats**: Various (H.264, ProRes source available for some)
- **Suitability**: ProRes and DNxHR source material. Useful for professional codec coverage.
- **Categories**: `happy-path/video`, `legacy-codecs/video`

### 6. ITU-R BT.2100 HDR Test Patterns
- **URL**: <https://www.itu.int/net/ITU-R/> (via EBU test sequences)
- **License**: ITU reference material (free for conformance testing)
- **Formats**: HEVC Main10 BT.2020, HLG, PQ
- **Suitability**: Normative HDR test patterns. Essential for HDR10/HLG transfer function validation.
- **Categories**: `hdr/video`

### 7. YUV Video Sequences (Arizona State University)
- **URL**: <https://www.hpca.ual.es/~vruiz/videos/>
- **License**: Research use (freely downloadable)
- **Formats**: Raw YUV (CIF, QCIF, 720p, 1080p)
- **Suitability**: Raw uncompressed source for testing encoder input paths. Multiple frame rates (24/25/30/50/60).
- **Categories**: `happy-path/video`, `high-resolution/video`

### 8. AV1 Test Vectors (Alliance for Open Media)
- **URL**: <https://storage.googleapis.com/aom-test-data/>
- **License**: BSD-style (AOM license)
- **Formats**: AV1 (IVF, WebM, MP4)
- **Suitability**: Conformance bitstreams for AV1 decoder/encoder testing. Covers all AV1 profiles and levels.
- **Categories**: `happy-path/video`, `edge-cases/video`

### 9. MPEG-2 Test Streams
- **URL**: <https://samples.mplayerhq.hu/MPEG2/>
- **License**: Free test material
- **Formats**: MPEG-2 (PS, TS containers)
- **Suitability**: Legacy codec coverage. MPEG-2 in MPEG-TS and MPEG-PS containers. 3GP container samples also available on the site.
- **Categories**: `legacy-codecs/video`

---

## Audio

### 10. EBU SQAM (Sound Quality Assessment Material)
- **URL**: <https://tech.ebu.ch/publications/sqamcd>
- **License**: Free for non-commercial technical use
- **Formats**: WAV PCM 16-bit/44.1 kHz (speech, music, tones, noise)
- **Suitability**: Industry-standard reference for audio codec quality assessment. Covers speech, solo instruments, orchestra, castanets (transients), glockenspiel (high frequency). Essential happy-path source.
- **Categories**: `happy-path/audio`

### 11. Xiph.org Test Vectors
- **URL**: <https://xiph.org/vorbis/>, <https://opus-codec.org/testvectors/>
- **License**: BSD / public domain
- **Formats**: Ogg Vorbis, Opus, FLAC reference streams
- **Suitability**: Conformance vectors for Vorbis and Opus decoders. Opus vectors cover VoIP (8 kHz), wideband (16 kHz), and fullband (48 kHz) modes. FLAC vectors cover 16/24-bit, mono/stereo.
- **Categories**: `happy-path/audio`, `edge-cases/audio`

### 12. McGill University MUMS (Multi-channel)
- **URL**: <https://www.music.mcgill.ca/resources/>
- **License**: Academic/research use
- **Formats**: WAV multi-channel (5.1, 7.1)
- **Suitability**: Multi-channel audio for channel-mixing validation. Covers 5.1 and 7.1 layouts that stress ChannelMixingAudioProcessor downmix paths.
- **Categories**: `multi-channel/audio`

### 13. Bowling Green State University Music Library
- **URL**: <https://www.bgsu.edu/library/music-library-and-bill-schurk-sound-archives.html>
- **License**: Public domain recordings available
- **Formats**: FLAC (16/24-bit, various sample rates)
- **Suitability**: Real-world FLAC content for cross-format transcoding tests. High bit-depth (24-bit) for precision testing.
- **Categories**: `happy-path/audio`, `edge-cases/audio`

### 14. HE-AAC Conformance Suite
- **URL**: <https://www.iso.org/standard/> (via 3GPP/MPEG conformance)
- **License**: Conformance testing (restricted distribution — download only)
- **Formats**: AAC-LC, HE-AAC (v1/v2), AAC-LD, AAC-ELD
- **Suitability**: Normative conformance bitstreams for AAC decoder validation. Covers all AAC profiles used in M4A containers.
- **Categories**: `edge-cases/audio`

### 15. Internet Archive Audio Collection
- **URL**: <https://archive.org/details/audio>
- **License**: Various (many CC / public domain)
- **Formats**: MP3 (CBR/VBR), FLAC, OGG, WAV
- **Suitability**: Real-world MP3 files with varied encoding parameters (LAME V0-V9, CBR 128-320). Covers ID3v1, ID3v2.3, ID3v2.4 tags including embedded cover art. Excellent for VBR edge cases (CRA-5 cross-ref).
- **Categories**: `happy-path/audio`, `edge-cases/audio`

### 16. Librivox Public Domain Audiobooks
- **URL**: <https://librivox.org/>
- **License**: Public domain
- **Formats**: MP3 (128 kbps CBR typical), OGG Vorbis
- **Suitability**: Long-form speech content for testing large-file audio compression. Mono speech — good for testing mono passthrough and downmix paths.
- **Categories**: `happy-path/audio`

---

## Image

### 17. Kodak Lossless True Color Image Suite
- **URL**: <https://r0k.us/graphics/kodak/>
- **License**: Unrestricted use for research/testing
- **Formats**: PNG (lossless, originally from PhotoCD)
- **Suitability**: THE industry-standard 24 images (768x512) for image compression benchmarking. Baseline JPEG and PNG quality assessment. Every image compression paper uses these.
- **Categories**: `happy-path/image`

### 18. LIVE Image Quality Assessment Database
- **URL**: <https://live.ece.utexas.edu/research/quality/>
- **License**: Free for research
- **Formats**: BMP, PNG (with DMOS scores)
- **Suitability**: Images with known quality scores. Useful for validating that compression quality settings produce expected perceptual results.
- **Categories**: `happy-path/image`, `edge-cases/image`

### 19. CID:IQ (Color Image Database for Image Quality)
- **URL**: <https://zenodo.org/record/2647033>
- **License**: CC BY 4.0
- **Formats**: TIFF, PNG (calibrated color)
- **Suitability**: Color-calibrated images for testing CMYK conversion, color space handling, and wide-gamut preservation.
- **Categories**: `edge-cases/image`

### 20. OpenEXR Reference Images
- **URL**: <https://www.openexr.com/images.html>
- **License**: BSD
- **Formats**: EXR (HDR, 16/32-bit float)
- **Suitability**: HDR image content for testing high-dynamic-range image handling and tone-mapping paths.
- **Categories**: `hdr/image`

### 21. RAISE (Raw Images Dataset)
- **URL**: <http://loki.disi.unitn.it/RAISE/>
- **License**: CC BY-NC-SA 4.0
- **Formats**: NEF (Nikon RAW), CR2 (Canon RAW), DNG
- **Suitability**: Real camera RAW files for testing DNG/CR2/NEF input handling. 8156 uncompressed images from three cameras.
- **Categories**: `edge-cases/image`, `high-resolution/image`

### 22. Adobe DNG Sample Images
- **URL**: <https://helpx.adobe.com/camera-raw/using/supported-cameras.html>
- **License**: Adobe sample (free download)
- **Formats**: DNG
- **Suitability**: Reference DNG files from multiple camera models. Tests DNG metadata parsing and RAW decode paths.
- **Categories**: `edge-cases/image`

### 23. CLIC (Challenge on Learned Image Compression)
- **URL**: <https://www.compression.cc/>
- **License**: CC BY-NC 4.0
- **Formats**: PNG (high-resolution, professional photography)
- **Suitability**: High-resolution (2K+) professional images. Tests large-image handling, memory pressure, and progressive decode.
- **Categories**: `high-resolution/image`

### 24. WebP Test Images (Google)
- **URL**: <https://developers.google.com/speed/webp/gallery1>
- **License**: BSD / Apache 2.0
- **Formats**: WebP (lossy, lossless, animated)
- **Suitability**: Reference WebP content from Google. Covers lossy, lossless, alpha, and animated WebP variants.
- **Categories**: `happy-path/image`, `edge-cases/image`

### 25. HEIF Conformance Test Files
- **URL**: <https://github.com/nicktencate/heif-test-content>
- **License**: Various (test content)
- **Formats**: HEIC, HEIF (grid, thumbnail, sequence, alpha)
- **Suitability**: HEIF/HEIC conformance content covering grid images, thumbnails, image sequences, and alpha channels. Critical for iOS HEIC handling.
- **Categories**: `edge-cases/image`

---

## Adversarial / Edge Cases

### 26. FFmpeg FATE Test Suite
- **URL**: <https://fate.ffmpeg.org/>, <https://samples.ffmpeg.org/>
- **License**: LGPL / GPL (test material freely available)
- **Formats**: ALL (every codec/container FFmpeg supports)
- **Suitability**: The largest public collection of media test files. Includes truncated files, unusual container/codec combinations, edge-case metadata, non-standard frame rates, and malformed headers. Essential for adversarial testing.
- **Categories**: `adversarial/`, `edge-cases/`, `legacy-codecs/`

### 27. libav FATE Suite
- **URL**: <https://samples.libav.org/fate-suite/>
- **License**: LGPL
- **Formats**: Various (codec conformance + edge cases)
- **Suitability**: Overlaps with FFmpeg FATE but has unique edge-case files for older codecs and container variants.
- **Categories**: `adversarial/`, `legacy-codecs/`

### 28. VLC Test Samples
- **URL**: <https://streams.videolan.org/streams/>
- **License**: Various (free test material)
- **Formats**: MKV, AVI, OGG, TS, various codecs
- **Suitability**: Real-world streams that exercise container parsing edge cases. Includes subtitled, multi-track, and chapter-marked files.
- **Categories**: `adversarial/`, `edge-cases/`

### 29. CERT Media Vulnerability Reproductions
- **URL**: CERT/CC advisories (CVE-based, case-by-case availability)
- **License**: Restricted (vulnerability disclosure context)
- **Suitability**: Malformed files that triggered real CVEs in media parsers. Critical for ensuring Kompressor doesn't crash on adversarial input. Files must be handled carefully and isolated.
- **Note**: Download per-CVE from advisories. Not bulk-downloadable. Use sparingly for specific known vulnerability classes.
- **Categories**: `adversarial/`

### 30. MPlayer/FFmpeg Samples Archive
- **URL**: <https://samples.mplayerhq.hu/>
- **License**: Free test material
- **Formats**: Comprehensive (A-Z codec/container coverage)
- **Suitability**: Organized by codec/container. Includes MPEG-2, DivX, WMV, RealMedia, and other legacy formats. Also has edge-case audio (AMR, ADPCM, GSM).
- **Categories**: `legacy-codecs/`, `edge-cases/`

---

## EXIF / Metadata-Specific

### 31. EXIF Orientation Test Images
- **URL**: <https://github.com/recurser/exif-orientation-examples>
- **License**: Public domain
- **Formats**: JPEG with EXIF orientations 1-8
- **Suitability**: Complete set of all 8 EXIF orientation values. Essential for validating Kompressor's EXIF rotation handling on Android (ExifInterface) and iOS (CGImage).
- **Categories**: `edge-cases/image`

### 32. Phil Harvey ExifTool Test Images
- **URL**: <https://exiftool.org/sample_images.html>
- **License**: Free for testing
- **Formats**: JPEG, TIFF, PNG, HEIC (with extensive metadata)
- **Suitability**: Images with complex metadata: multiple EXIF IFDs, XMP, IPTC, ICC profiles, GPS data, maker notes. Tests metadata preservation during compression.
- **Categories**: `edge-cases/image`

---

## Summary Matrix

| # | Source | Video | Audio | Image | Adversarial | License |
|---|--------|:-----:|:-----:|:-----:|:-----------:|---------|
| 1 | AVT-VQDB-UHD-1 | X | | | | CC BY 4.0 |
| 2 | Netflix Open Content | X | | | | CC BY-NC-ND 4.0 |
| 3 | Xiph derf's | X | | | | Various/free |
| 4 | BBB / Tears of Steel | X | | | | CC BY 3.0 |
| 5 | Blender Cloud | X | | | | CC BY 4.0 |
| 6 | ITU-R BT.2100 | X | | | | ITU reference |
| 7 | ASU YUV Sequences | X | | | | Research |
| 8 | AV1 Test Vectors | X | | | | BSD |
| 9 | MPEG-2 Streams | X | | | | Free |
| 10 | EBU SQAM | | X | | | Non-commercial |
| 11 | Xiph Vectors | | X | | | BSD/PD |
| 12 | McGill MUMS | | X | | | Academic |
| 13 | BGSU Music Library | | X | | | Public domain |
| 14 | HE-AAC Conformance | | X | | | Conformance |
| 15 | Internet Archive | | X | | | Various/CC |
| 16 | Librivox | | X | | | Public domain |
| 17 | Kodak Suite | | | X | | Unrestricted |
| 18 | LIVE IQA | | | X | | Research |
| 19 | CID:IQ | | | X | | CC BY 4.0 |
| 20 | OpenEXR | | | X | | BSD |
| 21 | RAISE | | | X | | CC BY-NC-SA |
| 22 | Adobe DNG | | | X | | Adobe sample |
| 23 | CLIC | | | X | | CC BY-NC 4.0 |
| 24 | WebP (Google) | | | X | | BSD/Apache |
| 25 | HEIF Conformance | | | X | | Various |
| 26 | FFmpeg FATE | X | X | X | X | LGPL/GPL |
| 27 | libav FATE | X | X | X | X | LGPL |
| 28 | VLC Samples | X | X | | X | Various |
| 29 | CERT Vulns | X | X | X | X | Restricted |
| 30 | MPlayer Archive | X | X | | X | Free |
| 31 | EXIF Orientation | | | X | | Public domain |
| 32 | ExifTool Samples | | | X | | Free |

**Total**: 32 sources covering all media types, codec profiles, and edge-case categories
required by the CRA-71 coverage matrix.
