# Simple Video Editor

## Build the current public source / 构建当前公开源码

Run from the checkout root containing `settings.gradle` and `gradlew`, not its
parent or an older checkout. Use JDK 17, the checked-in Gradle 8.7 wrapper, Android
platform 35 and build-tools 34.0.0 (AGP 8.6.1). Set `ANDROID_HOME` to your installed
SDK. CI pins command-line tools 12266719 and installs these packages explicitly:
the SDK action's default obsolete `tools` package is no longer available.

Windows PowerShell:
```powershell
$env:VIDEO_EDITOR_REVISION = git rev-parse HEAD
.\gradlew.bat testDebugUnitTest assembleDebug
```

Linux/macOS:
```sh
export VIDEO_EDITOR_REVISION="$(git rev-parse HEAD)"
./gradlew testDebugUnitTest assembleDebug
```

Use a clean checkout when stamping a commit. The APK is
`app/build/outputs/apk/debug/app-debug.apk`; a successful command alone is not
delivery evidence. CI runs the source/equivalence, complete rebuilt-APK and
outgoing-history privacy gates before uploading the `app-debug` Actions artifact.
Actions downloads may require GitHub login; this workflow does not create a
release or tag. Legacy build instructions below are not the current toolchain.

## Processing status, copies and output size / 处理提示、复制及文件大小

The localized editor still advertised 180-second export and 60-second import
limits after their timers were removed. Starting/progress/loading and software-AVC
help now agree in Chinese and English: **无应用内处理时限，可手动取消 / No in-app
processing time limit; cancel manually**. Obsolete import-timeout resources are
removed; the fixed status dock wraps to keep the explanation visible. Ordinary
exports disable Media3's muxer-stall deadline and only report elapsed time;
import/PNG copies check cancellation, not elapsed deadlines. Diagnostic/self-test
deadlines, media-size/dimension and merge-duration limits remain unchanged.
This does not promise unlimited Android/process uptime or instant interruption
of a blocked document-provider/native call. Storage and codec failures still apply.

默认直接读取 SAF 原视频 URI，不复制整段到应用缓存；预览、媒体探测、
原视频绝对时间的标题背景和导出使用同一来源。主视频普通编辑没有
128/256 MiB 导入限制，长度与复制计数均为 64 位。未知大小允许普通编辑，
但不允许依赖已知大小的受限合并。没有通过整段哈希验证 5–10GB 文件。

`VideoSource` distinguishes a user document from an owned cache copy. Descriptor
offsets/lengths and seekability are checked without a whole-file read. Only a
non-seekable descriptor (ESPIPE), or a virtual document with a video representation,
offers staging: a localized dialog gives the reason, source size (possibly unknown)
and free space. Nothing is copied until confirmation. Copy progress is cancellable
and has no elapsed-time deadline; partial owned files are removed on failure.
Permission, codec and ordinary I/O failures do not silently trigger copying.
Cloud providers may download internally: this is **not** a zero-network-byte promise.

Actual picker read/persistable flags are respected. Persisted grants and source
identity survive saved Activity/process recreation; providers without persistent
grants display a reselect-after-restart warning. Missing/revoked/changed sources
show a reselect error, not a misleading recovered cache. Keep originals available.
Replacing, cancelling or exiting never deletes user documents or releases a grant
another selection/recipe might still use. Grants are deliberately retained until
Android/provider/user revocation (many distinct picks can exhaust the system grant
quota; then new picks visibly become temporary). Saved legacy cache paths remain
supported only within the app cache.

PNG/music imports retain their copy semantics. Complete presets still own durable
assets (schema 6), not main-video/merge URIs: saving a selected intro asks separately
before copying its bounded durable asset; applying it creates a disposable working
copy. Old recipes/assets are unchanged. Existing merge limits remain: 128 MiB/clip,
256 MiB total, at most five appended clips and 120 seconds; no large-append claim.
No app-wide full-project recovery promise is added beyond Android saved state.

Owned selection copies survive saved recreation and are cleaned on replacement/
clear/finish, not immediately after export. Export intermediates are cleaned on
completion/failure/cancel. Output is still a private MP4 **plus** a public Movies
copy: budget up to two final outputs, plus composition work files and any confirmed
input/preset copies. Direct input does not mean zero disk use.

Native direct-access coverage uses a test-APK-only `DocumentsProvider` with
seekable, non-seekable, slow, unknown-size and logical >4 GiB sources; a second
provider supplies an offset/length-bounded descriptor. Run `DirectVideoSourceTest`,
`WholeEditPresetUriTest`, `MergeImportTest` and `UnlimitedProcessingTest`, then
`DirectVideoUiTest` through stock DocumentsUI, then `DirectVideoRestoreTest` in a
**separate instrumentation invocation** (it requires the preceding real SAF grant).
The latter restores the same saved source Bundle in a fresh process; it is not a
claim that a full edit project resumes after force-stop. Tests record cache delta,
provider reads and decoded/exported pixels, not import speed as a copy proxy.
Logical-length/short-fixture tests do **not** validate a physical 5–10GB phone export.

当前界面「输出分辨率」提供「原始（不缩放）」「高度 1080 像素」「高度 720 像素」
「高度 480 像素」：按最终高度保留裁剪／旋转后的宽高比，并非固定横屏尺寸。
想减小文件，选择**低于当前高度**的 720／480，或启用截取缩短时长；
小源视频选择更高的高度反而会放大。重新编码有画质损失，文件不保证比原片小。

Ordinary SDR encoding targets H.264/AVC video and AAC audio. The video bitrate is
automatically quality-targeted by the pinned Media3 1.5.1 device/codec/size/rate
policy, clamped to codec capability, trying VBR then CBR (not a fixed bitrate or a
target file size). Audio uses Media3 defaults; no audio-bitrate UI exists.
There are no user bitrate/CRF/target-MB, H.265/AV1, or independent FPS controls.
Playback speed is not an FPS compression setting; the encoder rate hint accounts
for source speed, other clips and generated titles without dropping frames.
「兼容编码（软件 AVC）」is a color/compatibility option, **not** a smaller-file preset.
「0%（静音）」affects original audio, not replacement/library music; lowering volume
does not itself lower its encoded bitrate.

For finer control, export once then use HandBrake or FFmpeg: lower average video
bitrate for a size budget, or use a quality setting (CRF/RF) for variable size;
H.265/AV1 may save space at similar quality but cost encoding time/compatibility.
Approximate size in decimal MB = `(video Mbps + audio Mbps) × seconds / 8`,
plus container overhead. For example, 2 Mbps video + 128 kbps audio for 60 s is
about 16 MB. Lower bitrates/resolution can lose detail; re-encoding is lossy.

## Independent title appearance / 标题外观与动画分离

标题的动画／显示消失方式、时长、文字颜色、字号和背景分别设置。
“加载完整预设”仍会恢复整个标题；只改动画请使用独立动画下拉。
颜色选择器复用边框色板，并提供 0–255 ARGB 滑块，不需要输入颜色字符串。
原视频帧背景仍使用原视频的绝对秒数（可位于主视频裁剪区间之外）及相同的
crop/SAR/rotation 几何；它不是封面，也不改变文字颜色或生成片头的时长。

Animation changes never reset appearance or duration. Choose 3 s, 5 s or a positive
32-bit integer duration in milliseconds. **Dissolve** fades text in and out over
up to 800 ms per end (half the duration for short titles); older animations retain
their original entrance and cut exit. Solid/gradient backgrounds and text colors
are independent, including alpha. Background alpha composites over black; a
source frame replaces the color background. Fonts, weights and multiline text remain editable.

Size is an integer **1–160**. Canvas layout uses reference pixels multiplied by
`min(outputWidth/640, outputHeight/360)`, wraps text and shrinks overly tall blocks
to their safe area. Legacy layout retains device-scaled sp, saved position and
explicit line breaks without wrapping. The UI labels the active unit; changing
animation does not change it. Preview and export use the same renderer.

Title JSON now has `schemaVersion: 2` and explicit `layout` (`canvas` or
`legacy-sp`), embedded unchanged in whole-edit schema 6. Unversioned/schema-1
titles map missing animation to `legacy`, font family to `sans-serif`, alignment
to `center`, frame background to false/time `"0"`. Legacy animation maps to
`legacy-sp`; the five older animated styles map to `canvas`. All existing colors
(signed 32-bit ARGB), alpha, size, duration, position, font and optional gradient
are preserved, including classic black, modern blue and red title presets.
Successful title-store migration writes schema 2; whole-edit titles normalize on
load and persist on save. Unknown versions, wrong JSON types and invalid values
produce visible errors, never default replacement presets or partial store rewrites.
Existing pinned title oracle assets and assertions are unchanged.

## Privacy release gate / 发布防泄露门槛

This is the fresh-history **public** source repository; original evidence and old
private history are not part of it. A locally rebuilt candidate alone does not
authorize artifact publication. Version 1 of `oracle_tools/privacy_export.py` derives
public metadata from the immutable source revision recorded in that tool. It
replaces exactly 44 host/tool/session metadata leaves with relative paths and
stable executable aliases, plus six internal music-authoring command paths,
then propagates SHA-256/byte-count dependencies.
Original evidence and archived APKs must stay private and byte-identical; their
old hashes are not hashes of the rebuilt candidate. License authors, public source
URLs, CC0 legal text, media fixtures, measurements, tolerances and assertions are
preserved. Personal publication drafts are runtime user data, never app assets.

Use Python's existing unittest runner for `oracle_tools/test_privacy_gate.py`,
then `privacy_export.py --receipt <private-receipt.json>` and
`package_phone_controls.py --check`. The export receipt proves exact deterministic
metadata equivalence and records unchanged asset SHA-256 hashes. `--write` is
only for deriving the versioned source candidate; never point it at raw evidence.
Old pinned oracle roots are a different provenance version: do not overwrite them
or run public-pin builders against them. The packaged public provenance is
checked offline; rebuilding authoring fixtures requires a separately derived
complete authority, not relabeling an original root as sanitized.
CC0 validation helpers accept an explicit `--evidence-root` for owned external
evidence; the default is `app/build`. Java uses the caller's `JAVA_HOME`/`PATH`,
not a recorded developer installation.

Run `privacy_gate.py --mode current --source-only --scratch <owned-directory>
--report <private-source.json>` before building, and run `--mode current
--artifact <rebuilt.apk> --scratch <owned-directory> --report <private-apk.json>`
afterward. Source includes tracked and nonignored files. Every archive entry is
streamed, hashed and scanned, including nested ZIPs disguised as binary files.
Qt/LMMS `.mmpz` payloads are bounded and decoded even when renamed. Recognized
Android binary XML is scanned as binary content, not misread as a Qt size word
and zlib header; malformed Qt declarations still fail closed. Binary XML parsing
also limits chunk/offset counts to 20,000 and individual reads to 8 MiB.
Encrypted, malformed, unsupported, excessively nested/large archives fail closed.
Bounds are 512 MiB per entry, 2 GiB cumulative expanded input, 20,000 entries,
three archive levels and an 8 MiB central directory. No third-party scanning
service receives private content. This is a finite pattern gate, not a guarantee
against every secret encoding or obfuscation.
Filename policy also applies to archive-root entries and every historical path
alias, not merely one name per Git blob. Mode-incompatible options fail closed.
Directory markers are untrusted filenames, not permission to skip validation:
nonzero declared payload sizes fail before opening/decompression; a bounded read
must confirm empty decoded content and surface CRC/read errors. Empty directories
receive filename checks, empty-content hashes and entry accounting (but no
file-extension content parsing). Entry and expanded-byte budgets are shared by
all inputs and nested archives; compressed empty directories are valid.
The export check includes the native intro manifest pin as well as the generated
video, text, watermark and music contract pins.

Separately run `--mode history --all-refs` (or explicit repeated `--ref` arguments
for outgoing refs), with the same scratch/report arguments, in a complete clone.
Known old privacy-bearing refs remain **blocked**, even when current inputs pass.
CI checks both scopes before upload; it does not erase history. Hash-specific
exceptions require exact path, rule, SHA-256 and reviewed rationale; no broad
author/email or license exceptions are used by default. Remote branch/tag/release
inventory and inspection of attachments/logs remain separate read-only work.
History rewrites, force pushes, tag moves, asset deletion, repository visibility
changes and publication require explicit approval. Preserve a verified private
offline history bundle and original artifact hashes before proposing cleanup.

Music preview validation reserves space before large evidence operations, but
bounded app cleanup and preference restoration must still run below that reserve.
Cleanup command failures remain explicit; a low-space run cannot claim PASS
without restoring its owned settings.

## Local publication drafts / 本地发布草稿

A failed restore clears only its completed recovery token, keeping draft fields
and unsaved edits intact. Retrying Save therefore captures its own operation if
the screen is recreated during the write. Actual revision conflicts still require
reopening the current draft; they are not silently overwritten.

After a successful public Movies/directory export, **发布草稿 / Publication draft**
creates or reopens a draft for **that exact output**. Creation is button-triggered,
not automatic on every export. Repeated taps/relaunch recompute the finalized
video's SHA-256 and byte count before global local-manifest lookup. **Open video
and find draft / 打开视频并查找草稿** also accepts an explicit system-picker selection.
Byte-identical renamed, moved or copied files reuse the same draft UUID and all
saved text, tags, privacy/audience/license intentions, cover binding and music
provenance; only the verified video binding and actual mediaName change.
**Encoding/editing normally changes bytes and SHA-256.** This is neither perceptual
hashing nor a claim that an original and its processed export have equal hashes.
**More / 更多 → More publication drafts / 更多发布草稿** lists, creates, opens and
deletes drafts. Deleting a draft never deletes video/cover files.

The separate Chinese/default and English editor saves title (100 UTF-16 units),
description (5000), newline-separated Unicode tags (30, 100 each, 500 total),
language (35), target platform, privacy intention (default private), and audience
(default unspecified), and `publicationLicense` (default `unspecified`; choices
`youtube-standard` and `creative-commons-attribution`). The CN/EN UI labels this
as **whole-video licensing intention**, separate from immutable music provenance.
Choose only with the required rights; YouTube Standard is YouTube-specific, not a
generic license for other platforms. CC0 music never makes a mixed video CC0.
A filename longer than 100 is retained intact in mediaName;
only its initial title is shortened with a warning. Save before leaving, discard,
or keep editing. Restoration waits behind the previous worker, reconciles its
durable immutable operation receipt and strictly monotonic revision, then overlays
only changed form fields. A prepared receipt binds the exact original source UUID/
revision (or explicit null source) to one exact target UUID/revision/content before
any target commit; recovery never replays target writes over later metadata. Missing,
expired, malformed or unfinished receipts fail closed, while changed/deleted targets
surface an explicit conflict. Media/cover bindings and music provenance come from
storage, not stale view state. Other-writer conflicts retain edits and show an error
rather than overwriting. Metadata validation does not revoke media availability;
successful saves re-probe both URIs off-main before sharing. Invalid forms cannot
share old text.

Music credits come from the completed export's immutable configuration, persisted
in a small independent per-output descriptor, **never from later editor controls**.
The 118 packaged CC0 tracks retain their actual catalog author/license/source.
Imported replacement audio and other source audio are not assumed licensed/CC0.
Older exports without a provenance descriptor require an explicit new draft and
media selection. SHA-256, byte count and duration are read on a worker; hashing
uses a 64 KiB buffer and long counters rather than loading whole videos. Back can
cancel; Android/provider native calls may still take time to return.

JSON manifests are local portable metadata, **not a YouTube ingestion format**.
SAF export writes the complete bounded manifest and verifies its bytes by readback.
Import enforces strict UTF-8/JSON, schema version 1, 128 KiB and nesting/field bounds;
v1 without `publicationLicense` migrates to `unspecified`, while unsupported values
are rejected without changing existing drafts. Local operation IDs are not portable.
Import creates a new identity with no video/cover permission. Device URI/path/identity/
status/auth extras are ignored and never exported. Explicitly pick a video to
compare its actual bytes. A differing hash in an open form requires confirmation
to find/create a **separate** draft; the old draft and provenance remain unchanged.
Covers in imported manifests require explicit reselection (persistent SAF read permission,
8 MiB/4096-pixel bounds); image bytes are not embedded in JSON. Missing/deleted
media or revoked permissions retain metadata and disable sharing with an error.
Manifests contain no credentials, but descriptions/names/credits may be personal:
share them only intentionally.

The derived index scans only bounded private JSON records (including legacy
SHA-256 records), not media directories or all videos. AtomicFile recovery reads
committed/backup records, never adopts a partial `.new` as a draft; invalid records
are retained rather than deleted and do not prevent lookup of other valid records.
Find-or-create and binding are serialized together. New videos are not saved as
half-fingerprinted drafts on cancellation/failure. Receipt retention is bounded:
new saves may evict older restore tokens, and an evicted/unknown token fails closed
instead of falling back to a draft `operationId`. Existing optimistic revisions,
atomic write/readback verification and lifecycle reconciliation remain enforced;
a stale editor/chooser must reload rather than overwrite another editor's changes.

There is no silent canonical choice among duplicate hashes: the chooser shows
title, UUID and provenance for every matching alternate (stable UUID order).
Selecting one changes only its binding; other drafts stay accessible in the list.
JSON import intentionally keeps a separate unbound identity. Imported hash claims
are not proof, are never used as the lookup query, and grant no URI access: a
real picker read must match SHA-256 **and** size, then the user chooses the imported
alternate or an existing draft. Even a sole unverified imported match requires
confirmation. Conflicting export recipe credits require an explicit existing
record choice or keeping this export evidence as a separate draft; credits are
never merged or replaced from current UI settings.

Hashing uses a 64 KiB buffer, 64-bit byte count, progress and cancellation off-main,
without a whole-file read or size/time abort. Pending MediaStore videos are rejected.
Descriptor size/timestamps and available provider size/modification metadata are
checked around hashing and again before binding. This detects ordinary mutation,
not an adversarial provider preserving all metadata. Providers are not immutable:
share checks readability and size but does not rehash gigabytes on every share.
The form warns to verify again if contents may have changed. Revoked/deleted media
never deletes metadata; explicitly selecting a verified identical copy restores
sharing with the new read grant. Portable JSON includes no device bindings,
verification flags, paths or credentials; cover bytes are not transported.

Copy title, description, tags, or combined text including credits using explicit
buttons. Video sharing uses ACTION_SEND, video/mp4, content URI, ClipData and
temporary read grants, with EXTRA_TEXT/SUBJECT/TITLE. **The target app may ignore
all prefill fields**; manually paste and verify. “Shared” means only handed to the
chooser, even if cancelled; it does **not** mean uploaded/published. Platform,
privacy, audience and license are local intentions, not actual cloud settings.
License intention is labeled in combined copy/share text; it grants no rights and
does not promise that YouTube or another target applies it. No OAuth,
YouTube API, upload, network permission or platform history integration is added.
Future authenticated upload would require a separately reviewed scope.

Draft regression coverage uses the native framework store/media/lifecycle/UI tests.
The test-APK-only `PublicationDraftProcessDeathFixture` supports host-controlled
API34 `--no-restart` checkpoints for `save`, `bind`, `switch` and `switch-conflict`:
capture state, release the pending operation after the chooser/save path is visible,
wait for the durable draft write or immutable private receipt, kill only the recorded
background PID, then restore the launcher task and verify either the full selected
draft metadata or an explicit conflict. The ready JSON includes `pendingOperation`,
`operationReceiptPath`, source/target identities and expected target/conflict data so
the host can confirm the exact durable restore token before killing the worker-owning
process. It is not an app endpoint or an automatically discovered unit test.

## APK download / 安装包下载

The fixed GitHub delivery folder is [`apk/latest`](https://github.com/jfpx/simple-video-editor/tree/main/apk/latest).
[Download app-debug.apk](https://raw.githubusercontent.com/jfpx/simple-video-editor/main/apk/latest/app-debug.apk).
Its `release-info.json` records the tested source revision, version, signing
certificate and validation counts; `SHA256SUMS` verifies the downloaded bytes.
This is a debug-signed build. The source revision identifies the local tested
source, and is not a promise that all source history is published in this repository.

## Unlimited ordinary processing / 普通处理不设时间上限

The former **180-second export** and **60-second import** app deadlines are removed,
not increased. Ordinary processing is not failed because 10 or 100 hours elapsed.
PNG watermark/preset asset copies also have no wall-clock deadline; interruption,
8 MiB/2048-pixel bounds, CRC and malformed-PNG rejection remain enforced.
`Media3ExportEngine` keeps asynchronous Transformer and progress polling; its
elapsed-time failure runnable is gone. Each pass sets Media3 1.5.1
`setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)` so slow/stalled muxer progress
also does not cause a time-based job failure. This does **not** change the native
500 ms surface-EOS handling, codec error handling, encoder selection or quality.
The offline **self-test only** still has its 15-minute suite, 180-second fixture
export and decoder/harness deadlines. Those are not user-video limits.

Video selection now reads the provider directly (see source-access policy above).
Only confirmed staging uses a reusable 64 KiB buffer and long counters; it never
loads a 4 GiB video into a byte array. Copy bytes/elapsed seconds and metadata
probing are separate stages. Cancel interrupts copying and rejects late callbacks;
provider/native calls ignoring interruption may return later. Retry gets a new
worker instead of queuing behind the cancelled operation.
Actual I/O, memory, disk-capacity, malformed-media and codec failures still fail.
Merge's existing **128 MiB/clip, 256 MiB aggregate, five appended clips,
120-second timeline and 4096-pixel bounds** remain explicit; they are not a
general 4 GiB main-video limit and have not been expanded.

The processing cost is not necessarily “just crop”: crop/rotation/trim use
decode → effects → re-encode; software AVC at
1080p may be much slower than real time. The selected Samsung software policy
is never switched off automatically. Music mixing may add a complete private
intermediate and second pass (video transmux); public Movies publication copies
the finished private MP4 once more. Reserve disk for **any confirmed source copy + final private
MP4 + public MP4 + optional intermediate**, plus media assets. Output size is
not reliably predictable from input size. No gain, resolution, codec quality,
music normalization or frozen-oracle tolerance is reduced to avoid failure.
Normal UI diagnostics retain bounded configuration/source-byte/geometry text,
selected encoder/configure records, pass and elapsed timings; progress continues
to show elapsed seconds even when percent is unchanged.

Keep the app foregrounded and powered for long work. The screen stays on while
busy, but there is **no foreground service or resumable encoder**: Android can
stop the process, thermal/storage/hardware failures can occur, and activity
destruction still cancels. No 100-hour OS/background guarantee is implied.
Explicit Cancel, cleanup and the existing durable public-Movies publication
journal remain. A native call cannot be forcibly made to finish safely.
The reported >4 GiB physical-phone export has not been reproduced here.

## Original-time title background / 原视频绝对时间标题背景

In **Title / 标题**, enable the generated title, then **Use original video frame
as title background** (default OFF). Enter original-source seconds, with up to
three decimal places: **0 ≤ t < original duration**. Changes extract automatically
after a brief debounce; **Extract / refresh** retries explicitly. Empty, partial,
negative, over-duration and sub-millisecond values show validation, never silently
become zero or clamp. The provider's metadata duration is used, including any
container/audio tail beyond a nominal fixture length.

Example: background **1.000 s**, main trim **2.000 s onward** still uses the
original frame at 1.000 s. MediaMetadataRetriever requests `OPTION_CLOSEST`;
the UI truthfully says **requested time / closest decoded frame**, because that
API does not return an exact decoded presentation timestamp.
Selection never reads a trimmed intermediate or shifts the time origin.
Source/time/geometry generation tokens reject stale asynchronous completions;
new sources and later crop/rotation/resize changes re-extract from the current
original source at the same absolute time.

Geometry is shared with the main static preview and production GL transforms:
metadata display rotation/SAR → four independent removed edges → user clockwise
rotation → aspect-preserving output-height resize → existing even-pixel alignment.
Four 10% removals retain the **central 80% on each axis**. A frame is projected
once into the final title canvas, not cropped a second time by the title.
No fit/letterbox or aspect distortion is introduced; arbitrary-angle rotation
has the same black exposed corners as the main video. Main-video color adjustment,
watermark and overlay text are not baked into this background.

Worker extraction uses a full-resolution frame, writes a unique immutable
app-private PNG, then builds a separate ≤640-pixel UI preview. Both source and
output must be ≤4096 pixels per side; larger/failed allocations report errors,
not silent quality fallback or omission of the title. Export decodes the
full-size PNG. Original colors/gradients remain unchanged when OFF. When ON,
the image replaces only the background; legacy and all five animated text
styles/fonts remain, the background does not scale/slide/fade with text, and
the selected border is applied once after title rendering.

**Save background PNG separately** publishes only the projected frame, without
title text/border, as `image/png` in the public **Pictures root** on API29+.
The selectable result shows the provider's actual name and content URI, dimensions
and requested original time; no guessed raw storage path. Older Android uses an
explicit image `ACTION_CREATE_DOCUMENT` destination. A pending/failed save is
not success. Deleting that public screenshot cannot break title export: the
private snapshot is independent. Pictures saves are not the Movies recovery
journal; abrupt process death during an image save can leave a pending image row.

Whole-edit presets **v6** and title templates store only **enable + absolute
seconds**, not the current video's pathname or old pixels. Applying to another
source validates its duration and re-extracts; too-short sources reject the
preset before changing settings. Versions 1–5 without these fields remain OFF.
Timestamp-only and enable-only preset changes invalidate old captures even while
loading; finishing the atomic apply explicitly refreshes without needing a spinner
event. Save and preview check the live source/time/crop/title state as well as the
capture generation, so a queued old callback cannot authorize stale pixels.
Idle locale/activity recreation retains the recipe and source URI/ownership,
then rebuilds the title PNG; missing media or expired grants require reselection.
Inactive controls are disabled individually. No new background-image importer
or cross-video pixel reuse is implied.

Validation uses the existing Java instrumentation and workflow, not a new
framework. `--phase features` additionally runs `TitleBackgroundTest`,
`UnlimitedProcessingTest` and `MergeImportTest` in both encoder policies and
`PngWatermarkImportTest` for real PNG decoding/cancellation/resource rejection, and
harvests `title-background-evidence.tar`; `--phase all` includes these plus
the unchanged full 17-export/60-control suites, short/report/merge/music checks.
The new tests exercise a simulated 101-hour elapsed clock, >4 GiB logical
stream with one 64 KiB buffer (no disk filling), real cancellation/error cleanup,
analytic crop/rotation/SAR, 3/5-second native titles with audio, Pictures pixels,
real Title-tab controls, stale requests, presets and CN/EN recreation.
Simulation is not a physical 100-hour or 4 GiB phone-export test.
`python -B oracle_tools\test_png_import_policy.py` executes the production PNG copy
methods with a virtual slow provider (61 seconds and 101 hours per read), without
sleeping for minutes or injecting a clock into the app. Review regressions also
apply timestamp-only presets in the real activity, compare public PNG pixels and
native 3/5-second title exports against the new original frame outside main trim.
Review progress: `app\build\title-preset-review-progress.txt`.
Commit first, stamp `VIDEO_EDITOR_REVISION` with full HEAD, and use the protected
APK/receipt and preserved-watermark-manifest arguments shown below. Keep new
candidates separate under `D:\jfpx\apk\title-background-candidate-<full-sha>`.
Progress/restart milestones are in `app\build\unlimited-title-progress.txt`.

## CC0 music intake

1. **Acquire rights evidence first.** Find the creator's original download and
   affirmative CC0 grant covering the actual recording and composition. “Free
   YouTube use”, royalty-free playback, embedding or a channel description is
   not permission to redistribute the raw recording inside an APK. Preserve
   source/license URLs, complete source/grant HTML, retrieval date, original
   filename and bytes, artist identity and any sample/project evidence. Reject
   unresolved third-party samples or contradictory grants; CC0 is limited to
   rights the affirmer holds, not a universal warranty.
2. **Keep the original.** Independently hash/download-check/decode it; inspect
   codec/channels/rate, exact Ogg final-granule frame count, full ending and repeat
   boundary. Label creator-asserted loops separately from whole
   `composition-repeat` works; never infer a seamless loop from a rounded
   duration. Do not normalize, fade, shorten or rename a composition as a loop.
   Preserve the six current originals and all protected receipts/candidates.
3. **Extend the existing catalog v1**, not a new framework:
   `music/<mood>/<style>/<track-id>.ogg`, stable `id`, `title/titleZh`,
   `mood/moodZh`, `style/styleZh`, `path`, `author`, `license`, `licenseUrl`,
   `source`, `sha256`, `sourceSha256`, `bytes`, exact per-channel `frames`,
   `format`, and explicit `playbackKind`. Use the existing `LICENSES.txt`,
   per-track notices, `provenance.json`/`expansion-provenance.json`, source
   evidence and `manifest.json` pins. Capacity is finite: ≤256 tracks,
   256 MiB declared audio, 1 MiB UTF-8 catalog (4 KiB average metadata/track),
   ≤1024 characters per metadata field and ≤12 MiB/track.
   Creator-loop and composition-repeat originals are bounded to 180 s; native
   44.1 kHz mono/stereo Vorbis is supported without changing the source bytes.
   Longer/unsupported material needs a separately reviewed feature change.
4. **Reproduce host and native checks.** Run
   `python -B -m unittest discover -s oracle_tools -p test_real_music_catalog.py -q`.
   `build_music_oracle.py` creates synthetic analytic controls only; do not
   regenerate frozen pins to bless new production output. Reuse its tools and
   existing catalog validation/FFmpeg analysis. Extend `RealMusicLibraryTest`
   to decode the entire new original and test actual per-granule looping,
   original mute, independent BGM 50/100% gain, real category selection and
   Play/Stop, restart/preset/CN/EN, Process and public Movies. Test default and
   software AVC. Verify exported PCM with `verify_real_music_exports.py` across
   every second, wrap and tail. Retain missing-repeat, wrong gain, muted output,
   oversize, malformed catalog, changed hash and unsupported decoder negatives.
   Do not weaken existing thresholds; numerical seams are not listening proof.
5. **Candidate gate.** Record pre-change hashes of the shared APK/receipt, all
   six originals and frozen oracle assets. Commit only explicit intended paths
   after reviewing rights and test evidence; build with full
   `VIDEO_EDITOR_REVISION`, run fresh `run_editor_workflow.py --phase all`,
   verify installed APK/test hashes, revision and signer, then seal a new
   candidate directory and its own receipt. Recheck protected hashes. Never
   overwrite the shared APK, delete old logs/candidates, or call a failed,
   incomplete, stale-revision or fixture-only run a release pass.

## Editor UI / 编辑界面

The editor defaults to Chinese. **更多 → 语言 / Language** switches to English
using the persisted per-app language setting; switching is disabled while importing,
restoring media, exporting or publishing. Existing text, cursor selections, selected
assets, preset choices, tab and each panel's scroll position survive idle recreation.
Source URI grants and any confirmed app-owned copies are retained for restoration,
not released/deleted by the old activity. Expired grants, missing originals or
evicted owned copies require reselection.

The fixed selected-frame preview and bottom action dock surround five peer tabs:
**画面 / 声音 / 标题 / 素材 / 更多** (Picture / Sound / Title / Assets / More).
Only the active settings panel scrolls. Landscape and the resized keyboard viewport
use a compact header/dock; no progress/result event jumps the page or focuses an input.
The preview is **static**, at the trim-start decoded frame: crop, rotation, output
resize, main-video color adjustment, PNG watermark and whole-video border only. It is not live playback or a composite timeline;
overlay text, speed, sound and intro/appended clips are not represented there.
The separate title scrubber previews the title's production-rendered frames and its selected border.

### Main-video color / 主视频调色

In **画面 / Picture**, enable **启用主视频调色 / Adjust main-video color**.
Brightness is **−100…100**, contrast and saturation **0…200%**.
Defaults are **OFF, 0 / 100 / 100**. Reset restores these three neutral values
without changing the checkbox. OFF retains slider values but bypasses processing;
ON with neutral values also inserts **no effect**, preserving exact identity.
Zero saturation gives grayscale. Controls lock during import/export/publication.
Whole-edit presets v5 save the checkbox and all three values, including disabled
values; v1–v4 recipes missing color settings mean OFF/neutral. Validation precedes
atomic preset application. Locale/activity recreation retains values.

The operation affects **only the main clip**, after source orientation/SAR, crop,
user rotation and output sizing, **before text, PNG logo and border overlays**.
Generated titles, imported intros, appended clips and their colors are unchanged.
Music mixing/looping, output location, sharing and encoder policy are unchanged.
This is not global color correction or a device-specific 601/709 workaround.

**Exact math:** normalize decoded SDR electrical RGB to 0…1, then decode each
channel to linear light using the pinned Media3 1.5.1 SMPTE170M transfer:
`L(E) = E/4.5` below `0.0812`, otherwise `((E+0.099)/1.099)^(1/0.45)`.
With `Y = 0.2126R + 0.7152G + 0.0722B`, `s=saturation/100`,
`c=contrast/100`, `b=brightness/100`, and `p=L(0.5)`, compute
`out = clip(c * (Y + s*(RGB-Y) - p) + p + b, 0, 1)`.
Thus contrast pivots around electrical mid-gray, brightness is a **linear-light
offset**, and clipping happens once after all three operations.
Encode via `E(L)=4.5L` below `0.018`, otherwise `1.099L^0.45−0.099`.

Preview and export share the same immutable parameters and column-major matrix.
Static preview treats the retriever's SDR code values with the video transfer,
not a Canvas/sRGB color matrix; processing is bounded to **640 px per side**.
Export uses a dedicated GPU shader which decodes electrical SDR to linear,
applies the matrix/clipping, then encodes SDR again. Media3 1.5.1's actual
`Factory.Builder` constructor uses `WORKING_COLOR_SPACE_DEFAULT` (electrical SDR),
despite the setter Javadoc saying linear. The global working space is **not**
changed. A plain `RgbMatrix` cannot express these transfer steps.
No per-video-frame CPU pixel loops or forced full-resolution bitmaps.
Preview/export use the same color math, not byte-identical decoded video: 8-bit
GL quantization, AVC/YUV conversion, source decoder differences and resampling
can differ. Non-neutral adjustment is SDR-only; HDR shader input is rejected.
Pixel parity is relative to the **device-decoded source** (saved unadjusted by the
native test), not a claim that Android's decoder and FFmpeg interpret every tagged
input identically. Independent FFmpeg evidence reports canonical-source differences
separately; it does not fit a correction matrix or enlarge acceptance tolerances.
The preview remains a static main frame, not a title/text/audio/timeline preview.
No actual Samsung hardware validation is claimed.

Reproduce on an already-owned, booted emulator (no new AVD or SDK download):
```powershell
$env:JAVA_HOME = 'C:\tools\jdk17'
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --jdk $env:JAVA_HOME --phase color --evidence-dir app\build\color-review
```
Commit source first: the workflow stamps/verifies the full `VIDEO_EDITOR_REVISION`
in app and test APKs, checks installed hashes/signatures and exact test inventory,
runs both existing encoder policies and restores the original preference.
Use a fresh evidence directory; a known pre-existing watermark manifest difference
requires its explicit `--preserved-watermark-manifest-sha`. `--allow-dirty` is
exploration only, never release evidence. `--phase all` preserves the full
17-export/60-control suites, short 3/10 paths and existing music/border/UI checks.
Color tests use independently calculated tones/RGB gradients, pivot/luminance/
clipping and negative controls, actual exports, geometry/overlay boundaries,
real sliders, preset/new-source round trips, locale recreation and public Movies.
The original test-only palette can be regenerated with existing FFmpeg using
`python oracle_tools\generate_color_adjust_fixture.py`; frozen oracle assets and
tolerances are not modified.

### Offline library BGM — two installed CC0 recordings

**声音 / Sound → Offline library BGM** is off by default. The original six creator-supported
CC0 originals by **isaiah658** and **Igor Gundarev** are installed, fully offline:

| Mood / style | Chinese / English title | Exact duration | Original bytes |
|---|---|---:|---:|
| Dreamy / ambient | 天境循环 / [Heavenly Loop](https://opengameart.org/content/heavenly-loop) | 1484075 / 44100 s (33.652494 s) | 1,224,922 |
| Suspense / ambient | 未解调查 / [Unsolved Investigation](https://opengameart.org/content/unsolved-investigation) | 48 s | 2,200,350 |
| Calm / ambient | 宁静氛围循环 / [Ambient Relaxing Loop](https://opengameart.org/content/ambient-relaxing-loop) | 1080932 / 44100 s (24.510930 s) | 1,284,993 |
| Underwater / ambient | 水下氛围铺底 / [Underwater Ambient Pad](https://opengameart.org/content/underwater-ambient-pad) | 735232 / 44100 s (16.671927 s) | 705,155 |
| Upbeat / synth composition | 快乐结尾 / [happy end music](https://opengameart.org/content/happy-end-music) | 1587712 / 44100 s (36.002540 s) | 585,189 |
| Cinematic / RPG composition | 旅程启程 / [the journey begins](https://opengameart.org/content/the-journey-begins) | 3263488 / 44100 s (74.001995 s) | 602,110 |

The original six total **6,602,719 bytes** of 44.1 kHz stereo Ogg/Vorbis, without
transcoding, trimming, normalization or replacement by test tones. Paths are
`assets/music/<mood>/<style>/<track-id>.ogg`; catalog includes CN/EN titles,
exact sample counts, original filenames, SHA-256 and source/license IDs.
Official source pages state CC0 and synthesis using ZynAddSubFX / LMMS.
CC0 permits raw redistribution and commercial use **within rights held by the
affirmer**, not universal third-party clearance, warranty or endorsement.
Attribution is optional but appreciated; credits and full source/license evidence
are bundled in `music/LICENSES.txt`, `music/licenses/`, `music/provenance.json`
and `music/evidence/`; additions also have per-track license TXT and
`music/expansion-provenance.json`. Igor's synthesizer-only LMMS projects were
statically inspected, not independently matched to the renders; preset rights
are not independently cleared. Summer Park and Kalimba remain excluded because
their instrument/sample chains are unresolved. No network
permission/download manager is added.
**More → Music sources and licenses** exposes the current status and catalog metadata.

Select a mood/style then a track (title/duration), enable the library and choose
**50–100% BGM volume**, default **100%**. **Mute original audio only** or original
volume 0 does not silence BGM. Otherwise the existing original gain applies to
the original timeline independently. Summed loud signals can clip; reduce gains.
Library ON explicitly overrides the imported replacement; OFF restores a selected
import. Importing a new replacement turns library OFF. Clear removes the library
selection. **Preview 10s / 试听10秒** auditions the selected recording for 10 seconds
starting at actual playback (not while copying/preparing), or until its earlier end.
**Stop audition / 停止试听** cancels immediately, including during preparation;
**Play full track / 整曲试听** remains available and plays once without looping.
Both audition modes use the
independent BGM gain (default 100%), never the video mix or original-volume setting.
Audition stops on leaving the Sound tab, opening categories, backgrounding,
recreation, selection change, import or export;
it uses audio focus and verified app-private bytes, without raw-asset permission.
The 10-second audition limit is UI-only: exported BGM still repeats the complete
recording across the full video. No preview duration is saved in export presets.
The actual catalog now contains **118 distinct original recordings**, totaling
**256,956,282 audio bytes (245.05 MiB)**: the unchanged six, 109 Abstraction /
Tallbeard Studios (Ben Burnes) originals (247,507,869 bytes), and Shrine,
Challenge Accepted and Blue Meadow In Green Sky (2,845,694 bytes).
There are 117 stereo originals and one mono original, *Ludum Dare 38 09*.
All are bundled for first-launch offline use; the single APK is consequently large.
Native Ogg preserves the approved originals and avoids uncompressed PCM bloat.
The 256-entry / 256 MiB guard remains a finite ceiling, not an inventory claim.
Abstraction's five source packs and complete CC0 licenses are recorded in
`music/abstraction-provenance.json` and `music/abstraction-evidence/`; the three
prior additions have `music/preview-expansion-provenance.json` and license TXT.
Official source: https://tallbeard.itch.io/music-loop-bundle . The creator does
not endorse AI/ML, NFTs or direct resale, although CC0 permits these uses within
the affirmer's rights. No endorsement or worldwide clearance is implied.
No source logos/art, held recordings or excluded archives are packaged.
Original titles remain intact; untranslated Chinese titles fall back to the
original title. Categories use creator metadata, not claimed perceptual review.
Mood/style groups remain available; **All tracks / search** and each group's
search match bilingual titles, mood, style and artist without opening audio.
The search box is above a separately scrolling bounded list. It must remain
physically visible with all 118 entries, not merely reachable through accessibility
actions. Native scale/real-library tests assert on-screen search bounds.
Catalog loading is bounded metadata-only. Only the selected track is streamed
through a 64 KiB hash-verifying copy off the UI thread; no bulk audio loading,
predecoding, external JSON trust, online download or new permission is introduced.
The expanded manifest pins every packaged original and provenance file; the six original audio hashes are unchanged.
The video preview remains static.

The shared production engine renders the original title/intro/main/merge timeline
first, preserving speed/pitch handling, then mixes looping library audio at native
speed while transmuxing video. Original mono/stereo audio is resampled to 48 kHz
stereo for mixing. Unsupported channel layouts fail, not silently drop audio.
The existing exact PCM timeline limiter trims the mix; AAC packet padding rules
are unchanged. Original Vorbis is decoded/resampled by the native pipeline; legacy
canonical PCM16 48 kHz stereo WAV support remains. Full recordings repeat; no
musical trim was inferred from rounded display duration. Author-advertised seamless
loops have numerical seam evidence only, not perceptual verification or a universal
gapless-playback guarantee. Speech/lyrics and all third-party samples were not audited.
Each export gets a bounded hash-verified, app-owned file deleted at completion/cancel.

Whole-edit preset v4 saves stable track ID, enable, independent BGM gain/mute and
versioned `library-mix` semantics, including inactive choices. Older presets retain
imported replacement behavior. Missing catalog IDs/assets or changed hashes produce
explicit errors, never a default track. Inactive imported assets remain in presets
so disabling the library can restore them. Language/recreation retains selections.
Catalog v1 licensing details are in `app/src/main/assets/music/LICENSES.txt`;
that pinned intake record describes the original 64-track/48 MiB/128 KiB limits.
Current capacity limits above supersede those historical limits, not its licenses
or per-track format/frame/hash requirements.
All music assets and provenance are pinned in `music/manifest.json`, with Git text
conversion disabled. Source duration is finitely bounded to 180 seconds, with
exact integer sample/granule counts; display duration alone is rounded. Optional
`playbackKind` permits `composition-repeat` recordings with a
bilingual **乐曲，可重复播放／衔接不保证无缝** /
**Composition; repeatable, not guaranteed seamless** hint.
Legacy/default `creator-loop` retains its designation, including the 61 new loops
over 60 seconds and the longest 154.5-second original. This is not an export-time cap.
Challenge Accepted and Blue Meadow retain conservative whole-composition repeat labels.
The two Igor tracks are complete compositions, not seamless-loop claims.
Underwater (16.67s) and Journey (74.00s) intentionally remain outside the preferred
20–60s without altering their originals. No perceptual listening claim is made.

The native Vorbis decoder emits padding beyond the final Ogg granule on the
API34 validation device. Each library loop therefore bounds decoded PCM to the
pinned original frame count **before** resampling; no musical samples or original
file bytes are trimmed. This is separate from the unchanged final timeline limiter.

Reproduce the offline import (approved local handoffs only):
`python -B oracle_tools\integrate_cc0_hundred.py --main D:\jfpx\cc0-100-library-work --preview D:\jfpx\cc0-preview-expansion-work`.
The importer pins the 243,213,356-byte delivery ZIP by SHA-256, checks all CRCs,
copies an allowlist without conversion, and maps staging metadata to the app schema.
The catalog and manifest are generated together; the host test pins that manifest.
`RealMusicLibraryTest#testAll118NativeDecodeAndStableRecipeRoundTrips` streams
every original through Android Vorbis and the production granule limiter, one
selected file at a time. Expanded rendering tests are representative, **not 118
full-video renders**: first/middle/last Abstraction entries, mono, longest loop,
and the three prior additions; 50/100% gain, 160s loop-crossing outputs, plus
mono-library/stereo-original mixing. Real UI search/preview/preset/CN/EN/Movies
checks use actual additions. Final receipts must state executed coverage and
any failed or unrun checks; historical baseline results are not new-run passes.
Run `python -B oracle_tools\validate_cc0_hundred.py --serial emulator-5580 --evidence app\build\cc0-hundred-validation-<unique-id>`
on an already-owned idle emulator. It builds a full-revision candidate under
`D:\jfpx\apk\cc0-100-candidate-<sha>` and verifies installed hashes, APK CRC,
all 118 original asset/provenance pins, previews, selected exports and old-six
regressions. It never publishes or overwrites the shared APK.
For a terminal run interrupted by the 8 GiB disk reserve, `--recover <prior-dir>`
can use a new evidence child of `C:\owned-evidence\cc0-integration`.
Recovery requires identical APK bytes, unchanged app/test sources, restored
preferences and complete passing stage inventories; old failure receipts remain.
No build, reinstall or complete native-stage replay is needed for that recovery.
After a picker-only fix, `--search-fix-from <complete-prior-music-dir>` builds a
new exact-revision APK and explicitly proves that only picker/test-visibility
sources changed. It freshly decodes all 118 tracks, runs both preview policies,
visible real UI/preset/Movies checks and 20 independent production PCM outputs.
The earlier 62-output/86-music-method renderer proof is referenced with hashes,
not relabeled as a run of the new APK. Catalog, audio, mix engine and PCM limiter
must be byte-identical for this scoped reuse.
Progress is appended to `app\build\cc0-hundred-integration-progress.txt`.
After that worker is terminal PASS, run
`python -B oracle_tools\validate_cc0_baseline.py --music-evidence <completed-music-dir> --evidence C:\owned-evidence\cc0-integration\<new-baseline-dir>`.
This verifies the same installed APKs, runs the unchanged full 17/60 suites in
both policies, actual short 3/10 paths, compact report/memory regressions and
all-118 immutable publication credits. Existing selftest artifacts are archived
before invoking the production selftest's normal retention policy.
`validate_cc0_picker.py --baseline <complete-baseline-dir> --evidence <new-C-artifact-dir>`
additionally drives physical first/middle/last catalog search, visible category
counts and explicit Preview/Stop, then publishes and independently checks a fresh
longest-track UI selection. It rejects reuse of an old Movies result.
Expanded short exports align their final half-second comparison to actual decoded
AAC EOS using the already-established bounded lag. This checks the actual final
samples even when a 4s output is 512 frames short with 2048 frames of priming.
Correlation (0.90), gain (12%), envelope (25%), lag-drift (256 frames) and packet
duration (1024 frames) tolerances are unchanged; missing-tail and wrong-gain/mute
negative controls must fail. The original-six checker retains its original windows.

#### Delivered 118-track candidate: measured coverage

APK source revision `f88cc38a1a2aeaab35c65906bdbf4850134d50c8`;
`D:\jfpx\apk\cc0-100-candidate-f88cc38a1a2a\app-debug.apk`,
**280,607,415 bytes**, SHA-256
`5dd11c6388c9a049462a082730d53027263a57d2444ec79dbf6c6dc9d96daaca`.
Debug signer/version are unchanged; no network permission or publication.
Later commits change only host validation/documentation, not this APK's build inputs.

Evidence root: `C:\owned-evidence\cc0-integration`.
`visible-final-01` passes strict APK CRC/186 music-file pins, all **118** streamed
Android native decodes with exact granule caps and unique native PCM hashes,
27 native methods, both 8-method preview policies, actual visible search/mono
audition/preset/CN/EN/Movies, and **20** independent production PCM exports.
`picker-final-03` physically selects actual global first/middle/last plus longest,
checks visible category counts and Preview/Stop, imports its own 4s fixture through
DocumentsUI and independently checks a **new** longest-track Movies export/share.
That brings fresh real-music PCM output coverage to **21**, not 118 full renders.
The screenshot taken while waiting at 99% is not terminal evidence; the fresh
MediaStore URI, output hash, PCM and share-chooser checks establish completion.

`baseline-final-01` passes **46** additional native methods: unchanged full
**17/17 exports + 60/60 controls in each policy**, actual short **3/3 + 10/10**
in each policy, report/memory negatives and all-118 immutable credits.
Short success remains **SUBSET_PASS**, not full-feature coverage. Production
retention may remove an earlier short run; both exact native assertion logs
remain, along with separately archived full suites and the retained short suite.
Combined fresh native method count: **73**, plus physical picker automation.

`validation-03` preserves the prior unchanged-renderer proof: 36 expanded and
26 original-six independent PCM outputs, both policy matrices and 86 synthetic
music regressions. It is explicitly prior-revision evidence, not new-APK coverage.
The final long-loop outputs retain original 154.5s/mono loops into 160s,
50/100% gains, exact constant AAC alignment and mono/stereo mixing. Missing-loop,
wrong-track, wrong-gain/mute and missing/wrong-original-mix controls are rejected
without relaxing correlation, RMS, frame or pixel tolerances.

Host checks include all originals/licenses/hashes and full native/8k-mono
fingerprints (6,903 exact pairs, no duplicates); all 109 Abstraction native PCM
hashes match their prepared receipts. This is not musicological identity proof.
Seven catalog/audio-oracle tests pass. Lint retains **nine identical inherited
errors**; the unrelated standalone schema harness still fails on its existing
JSONException multi-catch stub. The inherited **46/47 pixel** failure was not
rerun or weakened. Physical-phone/perceptual listening and full118 video-render
coverage remain unverified. Interrupted/failed exploratory runs are preserved;
only the named terminal PASS runs above are delivery evidence.

Additional real-library validation (not a substitute for the 86 synthetic music
regressions or full 17-export/60-control suites in both policies):
`python -B -m unittest discover -s oracle_tools -p test_real_music_catalog.py -q`
checks every pinned music/provenance file and full original decode. Run native
`com.simple.videoeditor.RealMusicLibraryTest` in each encoder policy, harvest
`files/real-music-evidence`, then run
`python -B oracle_tools\verify_real_music_exports.py --evidence <harvest-directory> --revision <full-sha> --output <result.json>`.
The original regression methods per policy still cover the six original fixtures, full native decode and exact
per-repeat granule caps, bounds failures, real category selection and Play/Stop,
54-second gain100/original0 and gain50/original-muted exports (80 seconds for
Journey, crossing its 74.001995s boundary), actual Sound-tab
Process/Movies/share, preset round-trip, bilingual recreation and activity restart.
The independent FFmpeg/numpy checker compares both source channels across every
second, wrap boundaries and the tail, with missing-loop/mute/wrong-gain negatives.
It records AAC alignment and a one-packet (1024-frame) tail quantization limit;
the exploratory 54-second AAC track was 256 frames (5.33 ms) short. No existing
oracle thresholds or production decoder bounds are widened. These are numerical
content tests, not subjective listening, physical-phone certification or release signing.
The complete Journey ending includes bins below one native PCM16 step. Relative
RMS gain is undefined there: those bins explicitly require absolute RMS error
at most 1/32768 instead of dividing by near-zero energy. They are recorded, not
omitted. Audible bins retain 25% envelope, 12% gain and 0.90 correlation checks;
an injected above-step ending fails, as do missing-repeat/mute/wrong-gain controls.

`python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase music --evidence-dir app\build\music-validation`
runs exact inventories in default/software AVC modes, including native 440/1320 Hz
independent-gain, resampling, loop seams, long title/intro/speed/merge, catalog/preset
negatives, real Sound-tab synthetic selection/MediaStore exports and unchanged
replacement/PCM/AAC regression tests. Test data injection is confined to androidTest.
`--phase all` also includes these checks without replacing the existing full/border
inventories. Apply the same explicit preserved-manifest argument described below.

### Colored border / 彩色边框

**画面 / Picture → 视频彩色外边框 / Colored video border** is off by default.
Choose opaque red, yellow, blue, green, white or black, **1–8%** thickness and
**Title only** or **Whole video**. Thickness is
`max(1, round(min(finalWidth, finalHeight) × percent / 100))` pixels on every side,
inside the final canvas. No padding, rescaling or aspect-ratio change is introduced;
interior pixels are untouched. Crop → rotation → output resize/clip fitting →
text/PNG or animated title → border is the visual render order. Four cached 1×1
opaque overlay textures form the edge strips; no frame-sized border bitmap is allocated.

Title only requires an **enabled generated title**, otherwise Process/preset
validation reports an error; imported intros do not qualify. The border ends at
the actual title boundary (including 3/5-second titles), not a hardcoded time.
Whole video includes generated title, imported intro, edited main and every appended
clip, including fitted/letterboxed edges. Speed-plus-music draws once in the edit
pass; the music pass transmuxes that video unchanged. Sound/encoding policies are
unchanged. Main static preview shows only Whole video scope; the title scrubber
shows either scope only when the generated title is enabled.

Whole-edit preset v3 preserves enable, palette, thickness and scope, including
disabled choices. Older presets without border fields remain off. Disabled borders
do not require a title or validate border geometry. Chinese/English switching,
recreation and busy controls preserve these selections.

Reproducible finite border checks use the existing instrumentation/pipeline:

```powershell
python -B -m unittest discover -s oracle_tools -p test_editor_workflow.py -q
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase border --evidence-dir app\build\border-validation
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase all --skip-build --skip-install --evidence-dir app\build\border-all-validation
```

`border` runs exact inventories in default and software AVC modes: independent
four-side/corner/RGB/alpha/thickness/interior arithmetic, omitted/wrong color/width/
scope/padding negatives, preset/default validation and real Media3 3/5-second
boundaries, crop/rotation/resize, intro/merge and two-pass music exports. Paired
border-off/on outputs and revision-stamped JSON/PNG evidence are harvested.
`all` also runs real picker/palette/width/scope/preview/Process/Movies checks,
Chinese/English disabled/busy-state checks and the unchanged short/full 3/10 and
17/60 default/software oracle suites. No frozen oracle pins or tolerances change.

Title OFF preserves all values and disables title inputs individually, while keeping
the master toggle, template manager and title preview available when idle. Whole-edit
presets, 0–50% independent edge removal, five animated title styles, multiline fonts,
merge, public Movies publication and software AVC retain their existing semantics.
Process, progress/Cancel, publication, errors and Open/Share/Location/Copy stay in
the dock. Displayed filenames truncate; the underlying output URI and copied details
are unchanged. Full raw technical diagnostics and the separate offline self-test
activity are available in **更多 / More**.

UI build validation uses Java 17 and `.local-sdk` with `assembleDebug`. Device,
keyboard, export/oracle regression verification belongs to the main test pipeline;
the UI build alone is not device-pass evidence.

From PowerShell, with Java 17 in `JAVA_HOME` and an already booted, exclusively
owned emulator, use fresh evidence directories for each invocation:

```powershell
python -B -m unittest discover -s oracle_tools -p test_editor_workflow.py -q
python -B -m unittest discover -s oracle_tools -p test_emulator_validation.py -q
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase ui --evidence-dir app\build\ui-validation
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase full --skip-build --skip-install --evidence-dir app\build\ui-full-validation
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase features --skip-build --skip-install --evidence-dir app\build\ui-feature-validation
```

Commit the intended build inputs first: the workflow stamps the full HEAD revision,
verifies matching installed app/test hashes and revisions, explicitly selects encoder
policies and restores the original preference. `--allow-dirty` is exploratory only.
An existing, intentionally preserved local watermark manifest requires
`--preserved-watermark-manifest-sha` with its exact SHA-256 on each command; the
exception is recorded, not silently normalized or committed.
The UI phase checks 30 native methods plus real video/PNG/imported-audio pickers, frozen preview
and exported-pixel checks, provider bytes, Open/Share and exact clear-preview pixels.
Only DocumentsUI captures use the platform's compressed accessibility hierarchy,
avoiding an API34 legacy dumper null-child crash; editor captures retain all fixed
containers. Capture deadlines, terminal errors and strict pixel checks still apply.
Full validation covers supplemental/report recovery and both 17-export/60-control
policies; features covers 26 base methods plus both title-background/policy groups. Do not run device phases
concurrently. Existing legacy `WrongConstant` lint errors remain outside this UI
scope; emulator results are not physical-device or release-signing certification.

#### Repeatable UI assurance gate

Use `--phase all` for a changed revision; `--phase ui`, `--phase color` and
`--phase music` isolate failures without relaxing assertions. The exact inventory
comes from the checked-in native method identities, not these documentation counts:
`UI_CLASSES`, `COLOR_CLASSES`, `MUSIC_CLASSES`, `REAL_MUSIC_CLASSES` and
`SUPPLEMENTAL` in `oracle_tools\run_editor_workflow.py`.

| Core flow | Checked-in evidence source |
|---|---|
| CN/EN, fixed preview/dock, real IME, landscape, title OFF gating | `FixedEditorUiTest` (IME explicitly reports unsupported environments) |
| Color/border, disabled values, presets, language/recreation | `FixedEditorUiTest`, `SelectedFramePreviewTest`, color/border phases |
| Actual video/PNG/audio document selection, clear, Movies bytes, Open/Share | `run_ui_workflow`, `SelectedFramePreviewTest`; saved screenshots/XML and frozen pixel oracle |
| Real CC0 categories, all six tracks, audition, independent mute/gain, restart | `RealMusicLibraryTest`, plus unchanged 86 synthetic music checks |
| Background/recreation/export stop, focus-loss callback, owned audition-copy cleanup, real Process/Cancel | `RealMusicLibraryTest#testAuditionLifecycleAndCancelExport` (focus callback injected, not a real phone interruption) |
| Actual 10-second MediaPlayer positions, delayed-copy/callback cancellation, early end, full-track choice, stopped/error UI | `ShortMusicPreviewTest` (no host-speaker or perceptual-listening claim) |
| 100/256 metadata entries, overflow rejection, lazy selected-copy verification, actual searchable picker | `MusicCatalogScaleTest` (synthetic metadata only, not 100 licensed assets) |
| Stale selected-frame/new-result races and publication failures | `SelectedFramePreviewTest`, `PublishedVideoTest`, `PublishedVideoSafTest` |
| Scoped ALL PASS vs SUBSET_PASS; short/full and large SAF TXT restoration | Full phase, `SuiteSummaryUiTest`, `ShortDiagnosticTest`, owned `saf-recovery` scenario |

Music/all now require the real-library inventory in **both** encoder policies and
the independent 54/80-second PCM checker. A unique invocation token prevents
older same-revision exports from filling missing evidence; missing, duplicate,
failed or wrong-policy records fail closed. Existing audible/pixel tolerances
remain; the below-PCM16-step ending check is explicit above.
The settings bridge snapshots and restores language, music selection and title
preferences as well as the encoder policy, then verifies them in a fresh process.
Failed restoration cannot pass; its owned snapshot is retained for recovery.
Milestones/PIDs are also appended to `app\build\ui-assurance-progress.txt`.
Cancellation remains terminal `CANCELLED` (130), with bounded owned cleanup.

Preview/capacity-only proof uses `oracle_tools\validate_music_preview.py --serial
<owned-serial> --evidence app\build\music-preview-capability-<revision>`.
It builds a separate exact-revision candidate, verifies installed APK hashes,
runs preview/capacity and real-library checks under both policies, independently
checks exported PCM, and restores/compares saved preferences in a fresh process.
This targeted scope does not claim a new full 17/60 video-engine run or promote
the candidate. `--recover <prior-evidence>` is specifically for the interrupted
six-track preview run's missing software-policy checks: it validates prior log
inventories and APK identity, retaining the original token, without repeating
successful default-policy long exports. Use it only on the already-owned idle
device. Each new attempt requires a new evidence directory.
Progress lives in `app\build\music-preview-scale-progress.txt`; its atomic summary
is saved before optional console output. A disconnected console cannot abort
validation or prevent terminal status. Primary exceptions, cancellation and
restoration failures are retained separately; SIGTERM/interrupt cleanup is
bounded by command deadlines. An OS hard kill still requires explicit recovery.

Expansion baseline evidence is retained in `app\build\ui-assurance-all-bd8d726`
(348 grouped native checks, both full 17/60 suites and 10 real PCM exports passed;
terminal FAIL on an ADB-server outage during the final UI screenshot).
`app\build\ui-assurance-bd8d726-adb-recovery` retains the same-APK retry:
real picker/pixels/Movies/Open/Share passed; native 27/28, with a clipped
border-scope control after the test's direct scroll collided with a pending
focus animation. The test reveal helper now cancels that animation like the
existing panel controller; strict full-visibility checks and production layout
are unchanged. These failed attempts are not relabeled as passes.
Candidate gate command (substitute the committed short SHA for `<sha>`):
`python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase all --evidence-dir app\build\music-expansion-proof\ui-music-expansion-all-<sha> --preserved-watermark-manifest-sha e273d3a974510f169b640fff38b76a770fac7c0fe834b090a46134f98ff28e37 --protected-apk D:\jfpx\apk\app-debug.apk --protected-receipt D:\jfpx\apk\delivery-receipt.txt`
Expected real-track inventory is 24 native gain exports plus two Sound-tab
Movies exports, six full original decodes per policy, and all-six audition.
Progress/recovery milestones: `app\build\ui-music-expansion-progress.txt`.
First expanded candidate `ui-music-expansion-all-2f4cc79` is retained as FAIL:
all eight real-library native methods passed, but the original RMS ratio checker
incorrectly divided Journey's below-step ending bins; the corrected comparator's
separate `diagnostic-quantized-ending-reanalysis.json` verifies all 26 retained
exports without relabeling that run. Supplemental UI was 184/185: its Preview
return helper recognized the package before the activity window was visible.
It now waits for the actual focused, visible window before the same real swipes.
Neither issue required a production engine/layout change. Final proof requires a
fresh all-gate on the new committed revision, not these diagnostic reruns.
The subsequent `ui-music-expansion-all-0fdea8d` retains PASS for all 26 PCM
exports, 185 supplemental methods, and both full suites, but remains FAIL:
the color test's separate reveal helper also needed cancellation of an earlier
scroll animation, and API34's hierarchy dumper twice exited zero with
`ERROR: null root node returned by UiTestAutomationBridge.` on stderr.
That error is now detected rather than parsing a missing XML file. One bounded
fresh capture shares the existing 30-second deadline/restart allowance; repeated
null roots, idle errors, other bridge errors and timeouts remain terminal.
Every failed capture's stdout/stderr and retry decision are retained.

#### Low-disk, harness-only UI recovery

Do not free space by removing retained logs, APKs, or assets. The gate checks a
2 GiB reserve for UI (8 GiB for other phases) **before creating reports**.
For a stopped run on a full project volume, prebuilt `ui`/`ui-host` can explicitly
own a **new**, absolute artifact root on another volume:

```powershell
$root = 'C:\owned-evidence\oracle-validation'
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 --phase ui-host --skip-build --skip-install --artifact-root $root --evidence-dir "$root\run" --candidate-revision 5d767e1f676335fee7050bbbad38346e8bf88a10 --preserved-watermark-manifest-sha e273d3a974510f169b640fff38b76a770fac7c0fe834b090a46134f98ff28e37 --protected-apk D:\jfpx\apk\app-debug.apk --protected-receipt D:\jfpx\apk\delivery-receipt.txt
```

The root must not exist. Traversal, symlinks, junctions/reparse points and sibling
escapes are rejected. Progress, command streams, Java classes/scratch and output
measurements stay under that root; SDK, source and existing APKs are read-only.
External roots intentionally reject builds and non-UI phases: do not assume
older full/music subprocess tools support external scratch paths.
Use an exclusively owned, already-booted emulator; this command does not create
or restart one. A cancellation file must be inside the approved root.

`ui-host` runs only the missing real-picker/preview/export/Open/Share stage; it
does **not** claim the 28 native UI methods or an ALL pass. `ui` runs both.
`--candidate-revision` is only for harness-only recovery: committed app assets,
production/test sources and Gradle inputs must be identical to that exact commit.
Installed/local app and test APK hashes and embedded revisions are still checked.
Any app/test/build change requires a new matching build and validation; harness
HEAD and candidate revision are recorded separately. Link existing exact-revision
native/music results without relabeling failed runs or older full-suite results.

Hierarchy failures are classified as automation infrastructure, not proof of a
product failure or success. Before the sole eligible retry, retain the actual
screen, window state, device memory/storage and logcat within the original
30-second capture deadline. Repeated null roots, idle/bridge errors, timeouts,
missing UI assertions and pixel mismatches still fail closed. No physical Samsung
hardware certification or seamless-composition claim follows from emulator passes.
`ui-music-expansion-all-126e523` passed the corrected scroll/SAF paths, 185
supplemental methods and 26 real PCM exports. It exposed a separate fixed-sleep
DocumentsUI readiness race (now a bounded wait for the real picker/search/document),
then host D: exhaustion interrupted smoke harvesting and even terminal persistence.
Its stale RUNNING summary is **not a pass**: `recovery-terminal.json` records ERROR,
and `owned-preference-recovery/verified.json` records independently verified restoration.
Only task-owned identical log copies were losslessly hardlinked and task-owned
evidence compressed, retaining every path and SHA-256; old/shared artifacts were
not removed. The new `music-expansion-proof` parent uses per-directory NTFS
compression to preserve complete raw evidence on the constrained host.
The `91303da` all-run passed all 348 native methods but host disk exhaustion
interrupted harvesting. A separate preserved recovery then rejected a stale
3-second prior UI result instead of the requested 54-second export. The real
music test now reads asynchronous UI state on the main thread, requires export
start and a newly published object/URI, and the independent checker requires
that freshness record in addition to original PCM/duration/geometry checks.
Native exit-zero alone is never treated as successful output evidence.

**Uncovered device-specific paths:** the output-directory button is intentionally
hidden on API29+ (automatic public Movies). API34 therefore does **not** validate
the actual API23–28 tree picker; helper/SAF tests are not equivalent. Real share
chooser opening is checked, not delivery to a third-party recipient. This gate
does not certify all OEMs, perceptual listening, or Samsung hardware color.
The older Samsung software-AVC 17/60 success remains separate evidence.

Workflow interruption handling includes preference restoration itself: a failed
restore/query stops its owned device-side instrumentation, not just the host ADB
client. Ctrl+C during failure-evidence capture remains `CANCELLED` with exit 130
while protected-file verification and remaining cleanup still execute.

Offline Java Android editor using **AndroidX Media3 Transformer 1.5.1**.
Android 6.0/API 23 or later; device AVC/AAC codecs are required.
No upload, network permission, or broad storage permission is needed.

Packaging recovery: the cleanup tool rejected `Stop-Process -Id $process.ProcessId`
before execution despite its explicit `-Id`. Identity-checked literal PID commands
were accepted on the authorized retry; no permission settings were changed.
An empty `foreach` is not evidence of a missing argument. Process termination can
finish asynchronously, so confirm exit before sealing. The reviewed `9b495082`
candidate was sealed successfully with its existing native-tested APK bytes.

## Samsung color / audio compatibility candidate

**For the reported Samsung export color problem, enable
`兼容编码（软件 AVC）` on the editor screen before exporting or starting the
full self-test.** This persisted setting is **off by default**. Each production
export snapshots it; it also applies to merge and both speed/music passes.
It uses only advertised, successfully configured software AVC encoders at
the requested size/rate. Unsupported configurations fail explicitly: no hardware
fallback, silent resizing or frame-rate reduction. HDR input is rejected in this
mode, including HDR in later merged clips. Software encoding can be much slower,
especially for large media; ordinary exports no longer have an elapsed/stall time limit.
Disable the option to restore the existing device/HDR selection policy.
Diagnostics record `encodingMode`, native encoder name and completed-pass backend.

The immutable Samsung SM-G991U1 / Android 15 report for `df73da5` completed
60/60 controls but only **1/17 exports passed**; 16 failed, without OOM.
Fifteen QTI-encoded outputs failed color checks. Signed raw-YUV measurements,
independent arithmetic and fully opaque injected PNG colors support a
post-overlay conversion/signaling mismatch (601-like samples advertised as 709).
The source SPS carries matrix 6 with unspecified primaries/transfer; Media3 1.5.1
defaults incomplete ColorInfo to BT709. Its SDR encoder EGL surface has no
explicit matrix-selection attribute. Neither observation proves a vendor-internal
cause or a source-tag-only repair. No matrix filter, output retag, fixture change,
oracle rewrite or tolerance increase is applied here.

Software resize and combo cleared color checks on that phone, but their smaller
geometry is **not** a same-size A/B experiment. Hence this is an explicit,
bounded workaround, not an automatic Samsung/QTI blacklist or a claimed universal
color fix. Real same-size 320x240 identity and opaque-watermark exports pass the
unchanged full oracle on the API34 software emulator. The user has since reported
**17/17 exports and 60/60 controls passing in software AVC mode on Samsung**.
This editor-workflow increment is validated separately on the emulator; it does
not claim to fix or unblock the Samsung hardware encoder's color path.

Combo additionally supplied PCM through 1.005333 seconds for a 1-second edit;
its AAC track decoded to 51200 rather than 48000 expected samples. The engine now
caps **final mixed PCM**, including mixer lead-in, at the full edited timeline's
sample budget. This includes generated titles, full imported/appended clips and
the final speed-plus-music pass; it does not cut audio to the main clip alone.
Unknown-duration legacy URI-only intros remain unbounded rather than guessed.
Native AAC delay/padding is neither fabricated nor subtracted. Exact-PCM native
AAC controls cover one-second combo, frame boundaries near one second, 0.5/2x,
silence/stereo, music and intro/merge; host tests also cover short PCM buffers.
Emulator AAC buffering differs from Samsung, so its success does not prove that
phone's final muxed duration. Confirmed 96-frame speed/music tails and the
500ms surface EOS timeout are unchanged.

The durable `3c09cef` result card and `df73da5` journal/memory behavior are retained.
Copy confirmation no longer repeats an older runner's ALL PASS over an external
or cancelled report. Candidate-specific final tests, APK/test-APK hashes,
revision/signature checks, fixture pins and limitations belong to the candidate's
own receipt in `D:\jfpx\apk\color-audio-candidate-<revision>`.
Crash-recovery progress is in `app\build\signed-color-audio-progress.txt`.
The protected shared APK, prior APKs/logs and shared receipts are not replaced.

## Reusable editor workflow

**Whole-edit presets** are separate from the older title-only templates. Enter a
name and use **Save / update** (the same name replaces that preset), then choose
a different video and **Apply preset**. **Delete preset** confirms before removal.
Up to 30 names are stored privately and survive activity/app restart.

Trim is stored as a **start offset plus milliseconds removed from the end**, not
an absolute end time or cached source URI. Example: select 1–3 s of a 4 s input;
the recipe retains 1–7 s of an 8 s input. A source of 2 s or less is rejected,
without changing the current edit. Millisecond source metadata is used exactly.
Disabled trim stores no cuts. Crop removals, rotation, output-height selection,
speed, volume enable/level, main text, title configuration/enable, imported intro,
replacement music, and PNG placement/enable are saved. Assets, including selected
but disabled PNGs, are copied to durable app files, bounded to 128 MiB each for
intro/music and 8 MiB for PNG. SHA-256 and size are checked before application;
missing/corrupt assets and invalid settings reject the **entire** apply.
Delete/update collects only unreferenced preset-owned assets.
Disabled PNG assets and placement settings remain selected when applying to a
different canvas; only enabled PNGs participate in effective geometry validation.
Re-enabling validates the retained image normally, without discarding its reference.

The input video and current appended clip list/merge checkbox are **not portable
preset content** and remain unchanged on apply. The global software AVC preference
and remembered output/report directories also remain unchanged. Applying a preset
validates its interaction with the current merge list. Original title-only JSON
defaults to the legacy static renderer; old retained crop saved view-state values
are converted, not reinterpreted (cuts outside the new range require correction).

### Original animated titles

Ten named built-ins provide **fade, slide, typewriter, scale, and lower-third**,
each at **3 or 5 seconds**. Each enters over 0.8 s and then holds; titles are silent
unless replacement music spans the composition. There are no sound-effect claims.
Choose built-in sans-serif/serif/monospace, normal/bold/italic/bold-italic,
left/center/right alignment, size 1–160, and midnight/ocean, burgundy/amber,
cream/black or black/white palettes. New titles use reference size 640×360,
proportionally scaled to the output canvas; long text auto-fits a safe area.
Multiline input supports 512 UTF-16 units and eight explicit lines, with real
word/long-token wrapping. The lower-third has a lower safe-area panel and sliding
entry, not just a different background color. No downloaded fonts/assets are used.

The separate title canvas and time scrubber render the same production layout and
timestamp effects; the main thumbnail still previews only selected-frame spatial
edits. Gray preview padding is outside the output. Legacy templates retain their
original static positioning/density-based sizing; choose a new style for safe-area
wrapping. Whole presets and title-only templates retain these new title settings.

Pattern research used the official public
[iMovie for iPhone title tutorial](https://support.apple.com/guide/imovie-iphone/add-titles-kna14aaa4db/ios)
(accessed with browserctl): animated title selection, editable text, position,
font, size, color, and timed preview. The companion
[Magic Movie/storyboard title tutorial](https://support.apple.com/guide/imovie-iphone/add-titles-audio-magic-movie-storyboard-clips-kna73cb53c2c/ios)
describes title layouts. These informed the workflow only; animations/layouts here
are original finite implementations, not copied templates, fonts or media.

### Checked-in Windows validation pipeline

Reuse an **already booted, exclusively owned** API34 emulator; this command neither
creates an AVD nor installs an SDK. JDK17, the existing SDK35 and cached Gradle8.7 /
Media3 dependencies are prerequisites. Close other instrumentation against that
serial first. Run from the repository root after committing scoped changes:

```powershell
python -B oracle_tools\test_editor_workflow.py
python -B oracle_tools\test_emulator_validation.py
# JAVA_HOME must identify JDK17; the SDK path and owned serial are caller parameters.
python -B oracle_tools\run_editor_workflow.py --serial emulator-5580 `
  --sdk .local-sdk --jdk $env:JAVA_HOME --phase all `
  --evidence-dir app\build\editor-final-validation-new `
  --cancel-file app\build\editor-final-validation-new.cancel
```

Use a new evidence directory each time; attempts are never overwritten. Optional
`--protected-apk <path>` and `--protected-receipt <path>` record and compare existing
file hashes, without writing either file. Only for an explicitly audited pre-existing
watermark manifest, supply `--preserved-watermark-manifest-sha <audited-sha256>`;
omit that exception on a clean checkout. The runner refuses other dirty build inputs
unless `--allow-dirty` is supplied, which stamps **unverified-local-source**.
`--phase features` runs real DocumentsUI crop/preset/public Movies/open/share
tests, durable preset checks, ten animated exports at four times each, and bounded
font/wrapping/palette assertions. `--phase full` runs the preserved 17-export /
60-control suite in default and software modes, the pinned 74-test inventory,
smoke, and separate short/report/memory/merge/publication regressions.
Stateful persisted-SAF/large-report recovery tests run only through the existing
`run_report_crash_validation.py --short --large-report` scenario, which first
creates a fresh owned TXT with the real picker. They must not be run standalone
against an older selected report.
New styles have their own assertions and **do not expand the frozen 17/60 claim**.
The feature inventory is now **25** (including the native disabled portrait-PNG
preset/landscape/atomic-rejection/re-enable regression); supplemental inventory
remains **172**, plus two separately orchestrated SAF recovery tests.
The small `androidTest` SAR fixture changes only the frozen source's display
metadata, not its decoded content; original frozen bytes are never modified.
Its reproducible derivation (write a new destination, never overwrite authority) is:
`ffmpeg -n -i app\src\main\assets\video-oracle\standard.mp4 -map 0 -c copy -bsf:v h264_metadata=sample_aspect_ratio=2/1 -aspect 8:3 app\build\sar2.mp4`.
An independent integer mapping checks real SAR2 display correction followed by
four 10% removals and 90° rotation, including native 192×512 output pixels.
The runner rejects missing/duplicate/failing test identities rather than trusting
adb's exit code; independent groups still run after an assertion failure, but any
failed group keeps the overall result FAIL. Short success must remain SUBSET_PASS
with full-suite status NOT_RUN. It records APK hashes/signatures and revision-tagged JSON,
archives output/UI evidence, checks protected-file hashes, and journals its PID
and progress under `app\build`. It never writes a shared delivery APK/receipt.
`--skip-build` / `--skip-install` verify local/installed app and test APK hashes,
then query the actual app revision and the test APK's build-generated revision
through a test-only instrumentation bridge. Both must match expected HEAD in
every phase. Both captured full suites and their export reports must match that
revision and installed app hash; an old 40-hex revision is not sufficient.
The bridge uses the production encoding-settings API: it records the original
preference, explicitly selects **default (off)**, then **software-avc (on)**,
and restores and re-queries the original setting even on failure/cancellation.
The harness waits for disk persistence via the settings API and confirms each
selection in a separate instrumentation process; an in-memory `apply()` result
alone cannot attest that the next process will use that mode.
Restored editor checkbox view state is synchronized to the global preference
before its change listener is attached, so stale view state cannot change policy.
Captured production diagnostics must record the requested policy and completed
encoder backends. Default may legitimately fall back to a software encoder;
different policies do not imply different encoder names on an emulator.
Optional
`--stop-emulator` shuts down only the verified, explicitly supplied emulator.
For a detached finite run use PowerShell `Start-Process` with a literal project
working directory, project-local stdout/stderr, and `-PassThru`; record its PID.
Use Ctrl+C in a foreground run, or create the supplied project-local cancel file
for a detached worker (`New-Item app\build\editor-final-validation-new.cancel`).
Cancellation returns **130**, records terminal **CANCELLED** stages, stops only
owned subprocess children and this app's instrumentation on the verified serial,
then restores preferences. Cleanup commands have finite timeouts (child tree
10+10 seconds, app stop 30 seconds, each settings call 45 seconds); failures are
recorded and cannot turn a failed/cancelled run into PASS. Do not forcibly kill
the worker if you need its `finally` restoration. Never kill adb/shared apps/AVDs.
Progress/PIDs are appended to `app\build\workflow-review-progress.txt`.
Root pitfalls: broad unittest discovery cannot initialize the original oracle
tool tests' required `--oracle-root` fixture; use their documented entry point.
For host-only privacy-gate fixes, run the targeted privacy suite and the existing
host suites; retain APKs built at their recorded revision when app/build inputs
are unchanged. Record the new gate/source revision separately, rerun source/APK
gates and canonical asset proofs, and write new receipts without replacing old
ones. Counts can increase because validated empty directory entries now count;
this is stricter coverage, not an asset change or a reason to rebuild the APK.
Legacy lint failures remain separate from native/test-inventory success, and
Gradle unit tests with NO-SOURCE are not counted as executed tests.
The existing standalone schema-stub compiler also currently rejects two unchanged
`SelfTestRunner` multi-catches because its `JSONException` stub extends
`RuntimeException`; this is not a native Android compilation failure.

## Editing

1. Select a video using the system document picker. The app copies it to
   private cache so export reads a stable source. Merge-mode video snapshots
   are bounded to 128 MiB and 4096 px per side.
2. Optional merge is **off by default**. Enable appended merge, then select
   one to five additional clips with the system document picker
   (`ACTION_OPEN_DOCUMENT`, `video/*`, `EXTRA_ALLOW_MULTIPLE`). Import is
   atomic: cancel or any validation failure leaves the previous appended list
   unchanged. Main + appended clips are capped at six total clips; copied
   video snapshots (main + intro + appended clips) are capped at 256 MiB.
   Total exported timeline, including title and imported intro, is capped at
   120 seconds. **Up/Down** reorders only appended clips; **Remove** deletes one,
   and **Clear appended clips** deletes all. Main stays anchored. Selecting again
   atomically replaces the appended list, not adds to it. Turning merge off
   retains the list but excludes it from the export. Reorder/import/edit controls
   are disabled during loading/export; provider changes after import cannot
   alter copied clips. Selection copies are released on removal/replacement or
   screen destruction, after active export cancellation has stopped.
3. Set trim start/end in seconds within the source duration.
4. Crop fields mean **percent removed independently**: left/top/right/bottom,
   initially 0/0/0/0, each 0–50 inclusive; opposing sums must be below 100.
   Four 10% values retain central 80% × 80% (320×240 → 256×192).
   The internal immutable `EditConfig` and old oracle coordinates remain retained
   bounds; no legacy right/bottom=100 is reinterpreted as removal.
   Media3 Crop shrinks the canvas before rotation and aspect-preserving height
   scaling, not a black mask. Gray space outside the fit-center thumbnail is UI
   only; arbitrary-angle rotation and aspect-fit imported clips can still letterbox.
   Coordinates refer to the display-oriented source **before user rotation**.
5. Rotate clockwise/right or counterclockwise/left, or enter a custom angle.
   The static thumbnail decodes the nearest frame at the validated trim start
   (zero when trim is off), then applies crop, rotation, output aspect/size and
   enabled PNG watermarks using the export geometry. It is not live playback
   or a text/speed/audio/intro/merge preview. Invalid or partially typed edits
   retain the last good frame with a validation message; rapid edits discard
   outdated decode requests. Work is off the UI thread; displayed frames are
   bounded to 640 px per side. API27+ requests scaled decoding. API23–26 must
   decode one full frame first, so sources above 4096×2160 pixels are rejected
   for this static preview rather than risking an unbounded allocation.
6. Select original size or final output height 1080/720/480, preserving aspect
   ratio. Add centered text, choose 0.5–2x speed, or enable volume adjustment
   (0% removes audio; 50–300% applies PCM gain, clipping at full scale).
   Audio is retained by default; speed changes preserve pitch.
7. Optionally select a **PNG watermark**, then enable it (off by default).
   Set width to 5–50% of the final canvas and X/Y to 0–100% of remaining
   free space: 0 = left/top, 100 = right/bottom. Transparent pixels and the
   image aspect ratio are retained; an image too tall at the requested width
   is rejected rather than cropped or silently resized. **Clear PNG watermark**
   removes the selection and disables it.
8. Process, or cancel. Ordinary encoding has no elapsed/stall deadline. On Android 10/API29+
   successful MP4s are published in the **public Movies root**, not an app
   subfolder, using MediaStore with no broad storage permission. Find them in
   Movies with your file manager/gallery. The result card persists across restart
   and shows the provider's actual name/location and content URI (not a guessed
   physical path); use Open, Share, or Copy video location / URI.
   Android 6–9 asks for a writable output video directory using SAF and remembers
   that grant. You may change that directory with its dedicated button.
   Android 11+ restricts SAF selection of the Downloads/storage roots; the
   Movies MediaStore default avoids that restriction.

Encoding still stages privately under `files/exports`. Only a fully copied,
finalized MP4 is called published; cancellation/failure removes pending public
copies. Interrupted publication is recovered on next launch. Provider failures
retain the good private MP4 with explicit failure status and Open/Share access;
private copies disappear on uninstall. Public copies survive uninstall.
Diagnostic TXT destinations and selected report folders are unchanged.
Publication recovery journals rollback intent **before creation**, so a cancelled
or failed MediaStore row cannot be mistaken for success after finalization, even
if subsequent metadata writes also fail. Only the successful result commit
removes that intent. Legacy finalized journals without rollback intent can still
be adopted; an unavailable provider state blocks recovery rather than guessing.
Failed replacement publication preserves the previous public URI/name and its
private-file binding, while retaining the new good private MP4 separately.
Android `SharedPreferences.commit()` changes memory even when disk persistence
returns false. Every publication metadata commit snapshots the binding and
journal, restores both in memory before any rollback persistence, and reports
the original failure (including restoration failures). The single preferences
XML/backup remains authoritative: no duplicate metadata store or blanket
preference clearing. If storage stays unavailable, the last durable reservation
and rollback intent retain cleanup responsibility for restart/retry.
SAF cleanup confirms absence with a successful listing of the selected creation
directory, including after deletion or FileNotFoundException. Permission denial,
null/failed listings and providers that acknowledge deletion without deleting
keep the bounded journal for retry. Interrupted SAF copies are discarded, not
adopted as public success. These fault boundaries are tested with a wrapped
DocumentsProvider on API34, **not** a physical API23 device or every SAF provider.
Video copy uses `Os.fstat`/`S_ISREG` before filesystem sync: MediaStore/local regular
files retain `fsync`, while pipe-backed SAF outputs finish under the provider's
close contract. This does **not** promise cloud-server durability. Descriptor
inspection, genuine regular-file sync errors and close errors remain failures;
no permission widening or silent private-success fallback is used.
Providers must expose the completed document when reopened after close; a
provider exposing a stale/partial file fails byte-count verification rather than
being reported as successful. The delayed pipe-provider test exercises the same
copy-and-reopen verification path as publication, not just a standalone write.

Native publication/preview checks (reuse an already-running API34 emulator):
```powershell
$env:VIDEO_EDITOR_REVISION = git rev-parse HEAD
.\gradlew.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest
.\.local-sdk\platform-tools\adb.exe -s emulator-5580 install -r app\build\outputs\apk\debug\app-debug.apk
.\.local-sdk\platform-tools\adb.exe -s emulator-5580 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
.\.local-sdk\platform-tools\adb.exe -s emulator-5580 shell am instrument -w -r -e class com.simple.videoeditor.PublishedVideoTest,com.simple.videoeditor.PublishedVideoSafTest,com.simple.videoeditor.SelectedFramePreviewTest com.simple.videoeditor.test/android.test.InstrumentationTestRunner
```
The inventory is 26 publication, 15 wrapped-SAF/helper and 4 selected-preview/UI tests.
The regressions cover Android-style memory mutation on false commits, repeated
disk failures, real Android XML-write failure and fresh-instance durable recovery,
retained cleanup journals, a native pipe-backed DocumentsProvider with 196,613
byte equality, a real regular-descriptor sync failure and provider close failure.
The UI test uses the real picker, trim/crop/rotation controls, Movies export,
independent pixels/bytes and Open/Share; emulator success does not validate Samsung.

Crop → rotation → aspect-preserving resize →
text overlay → PNG watermark are applied in that order, on the main video only.
Before overlay, output dimensions
round up to even pixels for AVC (at most +1 pixel per dimension); the entire
selected rectangle is retained. Trim selects the source interval;
speed changes the resulting duration. Merge composition order is generated
template title → imported intro → edited main → appended clips. Imported intro
and appended clips keep their full native duration, preserve source display
rotation, and are aspect-fit/letterboxed onto the edited main canvas. Main
trim/crop/rotation/speed/text/PNG edits never apply to imported intro or
appended clips; the app displays that policy explicitly instead of silently
ignoring controls.

PNG selection uses the real system document picker, with no storage permission.
Only static, structurally validated PNGs up to 8 MiB and 2048×2048 are accepted;
bad signatures/chunk CRCs, truncation, animated PNG and excessive dimensions
fail explicitly. Copy/decoding runs off the UI thread without an elapsed-time
abort. A unique private-cache copy and a private decoded snapshot
isolate exports from later provider/file/widget changes. Cancelling selection
preserves the previous image; selections are not restored after screen destruction.
The bounded thumbnail uses the same final-canvas placement and alpha rules as
the Media3 bitmap overlay (above text, not on titles/imported intros). It is a
pixel-rounded spatial preview, not an exact moving-frame/color-managed playback.
Cache selections are removed when replaced, cleared or the screen is destroyed.

**Fast mode is retired:** Media3 controls optimization; no enabled edits are
silently ignored. Composition order is generated static/animated template title →
imported intro → edited main → appended clips. Titles use the saved
style/duration; imported intros and appended clips fit the final canvas with
letterboxing at native speed. Main edits do not affect imported clip
geometry/timing. When replacement music is not selected, original intro/main/
appended audio is preserved in exact timeline order; silent first/middle/last
clips stay silent without shifting later audio. Source gain applies only to
retained original audio. Replacement music removes original audio, loops at
native pitch/gain from time zero, and stops with the video (including titles,
intros and appended clips). Speed plus music uses a two-pass Media3
composition to establish the final video duration before looping audio.
Unsupported dimensions/options fail explicitly. There is no silent legacy
fallback. Revision `70f003d9d12cbcff88c6004098eccf09e2db313b`
completed native validation on the API 34 AOSP x86_64 emulator with WHPX and
SwiftShader: all 16 production exports, 56 checker controls and 69 regression
tests passed, along with the rotate-90 smoke test. Independent FFmpeg inspection
of the actual exported files passed 16 metadata comparisons and 174 audio windows
(171 tone windows and three silent-title windows). Evidence is retained in
`app\build\emulator-validation\final-revision-validation`, including the original
app-data archive and `independent-analysis\independent-summary.json`.
That earlier delivered APK matched the installed/tested SHA-256
`38640007c57313962e8866a2a7a9eb0259aad07d7ee3eb05bfb9a8164b52e953`.
The owned emulator was stopped after collection. These results establish actual
Android execution, not universal OEM hardware-codec, font or format compatibility.

## Supplemental merge validation

The bounded multi-video merge supplement adds native instrumentation fixtures in
`app\src\androidTest\assets\merge-fixtures` and production-engine tests:

```powershell
.\gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.simple.videoeditor.MergeCompositionTest
.\gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.simple.videoeditor.MergeExportTest
.\gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.simple.videoeditor.MergeImportTest
```

These exercise the real `Media3ExportEngine` merge contract, source-audio
retention/silent gaps, replacement-music looping, native-speed appended clips
with fast/slow main edits, gain/mute, config immutability, bounds, native document
copy isolation and cancel cleanup. Supplemental fixtures are generated with
direct MediaCodec/MediaMuxer from analytic colors, framecodes and tones, not by
the production export engine. Rotated source coordinates are checked independently;
non-frame-aligned trim is checked within one source-frame quantization. Frozen
baseline fixtures and thresholds are unchanged.

Actual API 34 emulator runs and independent host FFmpeg RGB/PCM checks are retained
under `app\build\merge-validation`, including original failing runs, exported
media, exact instrumentation inventories, UI hierarchies/screenshots and build
logs. The pre-candidate native run passed all **17 supplemental tests**
(6 composition/config, 7 export/cancel, 4 import); independent FFmpeg checks
passed the retained-source, muted and replacement-music merged exports, checking
images at every clip transition and tone/silence windows, not just duration.
This supplement is counted separately from the inherited 17-export/
60-control full suite, 74 regressions and 70 focused native tests. The short
diagnostic remains 3 exports/10 controls and must report `SUBSET_PASS`, never
full-suite PASS. Candidate-specific results and installed APK hashes belong to
the separate merge-candidate receipt; the shared diagnostic APK/receipt are not
updated. Samsung color/signaling and EOS-tail behavior remain unverified and
are not fixed or overridden by merge. Existing lint still reports 10 legacy
errors; local unit-test sources are absent.

## One-click offline self-test

### Result at the end of the previews

Scroll past **all Preview buttons** to the persistent **本次自测结果** card.
The top status also shows the result. The card identifies the selected mode,
export/control passed, failed, error and unrun counts, and a reason/next step.

- **ALL PASS** requires a completed FULL suite with all **17/17 exports and
  60/60 controls** passing: **本套件已覆盖项目全部通过**. This is not a claim that
  every feature, context or device is covered. Coverage limitations remain separate.
- **SUBSET PASS / 短诊断所选项目通过** means only the selected **3/3 exports and
  10/10 controls** passed; 14 exports and 50 controls were omitted. It never
  displays ALL PASS.
- **未通过** is red. For example, **1/17 exports passing, 16 failing**, even with
  **60/60 controls passing**, is a failed suite. A previewable file is not an
  oracle PASS. Running, cancelled, interrupted and storage-error runs are not green.
  Once test execution/cleanup finishes, Cancel is disabled while the final result
  is being saved; a pass appears only after that durable save succeeds.

Reopening restores a bounded, validated structured `suite.json` result bound to
the latest run/report and selected mode, without rerunning or guessing from TXT
text. Missing, stale, malformed, inconsistent or unreadable metadata cannot
produce a pass; older unbound results require a new run. Opening an external TXT
shows a diagnostic preview, not an inferred verdict. No Samsung color/tail fix
is implied by this result display.

`suite.json` is a **compact index**, not a diagnostic dump. Previously, ordinary
17-export ERROR runs duplicated full run metadata and stacks into the index,
exceeding its 256 KiB reader limit and breaking both reopening and bound-report
sharing. Export/control rows now allowlist IDs, expected/actual/aggregate status,
optional hashes and relative JSON report references. Fixed inventory/field bounds
and an encoded-byte preflight enforce the same 256 KiB limit before serialization;
no diagnostics are silently truncated. Strict provenance and saved outcome checks
remain unchanged, including SHORT's 3/10 `SUBSET_PASS` rules.

Full per-case measurements, metadata, configuration and exception stacks remain
in the individual JSON reports and existing TXT journal diagnostics. Control
exception reports also retain full run metadata. The atomic `run-metadata.json`
manifest holds complete run metadata, raw report binding and fatal/storage stacks;
the index references these fields without embedding them. Bounded source-revision
and installed-APK hashes remain inline for existing validation tooling. Per-case references are
published only after a successful atomic write. Failed writes alone retain their
complete original payloads and storage errors under the manifest's
`failed_report_writes` field, including verifier results preceding an error row;
normal manifests do not duplicate the result inventories. A raw write failure
remains `STORAGE_ERROR` even when this independent fallback succeeds. If both JSON
destinations fail, no new index is committed and `RAW_DIAGNOSTICS_UNAVAILABLE` is
reported in the live diagnostics and, when writable, the private TXT journal.
The fallback is streamed separately and never embedded in the bounded index.
Binding v2 stores fixed
SHA-256 digests of the complete selected URI/destination and validates the current
selection before sharing its exact URI. Bounded legacy v1 bindings still reopen;
oversized legacy indexes explicitly report a non-green size error, without an
unbounded recovery path or a raised reader cap. Report generations, journal
commits, finalization and coalesced UI updates are unchanged.

Native `CompactSuiteIndexTest` covers production reporting with real contract/run
metadata, 17 injected export errors plus 60 control errors, exact raw diagnostics,
huge bindings/stacks, atomic storage failures, cancellation, legacy size rejection
and provenance tampering. Per-case and staging-file fault injections cover original
control/export stacks, post-export verification payloads, and dual-destination
failures without dangling new references or false passes.
API34 development validation passed **90 tests**:
29 compact-index, 28 outcome, 8 short-diagnostic, 6 summary-UI and 19 report-I/O.
The all-error index was **18,586 bytes**, with **4,024,344 bytes** retained in raw
case reports. These are reporting regressions, not a new phone codec verdict.

After installation, open **Run offline self-test**, then **选择 TXT 并开始**
(choose TXT and start). In Android's **Create document / Save** dialog, select
the phone's **Downloads / 下载** and save the proposed new `selftest-<time>.txt`.
No test starts until the document is writable and its read/write grant is
persisted. Cancelling the picker does not start the suite. Use a **new empty**
file; existing reports are never truncated. No broad storage permission is
needed, including Android 6–9 (API 23–28).

### Targeted phone diagnostic (additional entry)

**选择 TXT 并开始** remains the full/default suite: **17 exports, 60 controls
(22 positive / 38 negative)**. The existing `start()` and `start(savedReport)`
APIs still select this full inventory.

Choose **短诊断：选择 TXT（仅 3 项导出）** for the bounded Samsung evidence
shortcut, then save a new `short-diagnostic-<time>.txt` in local Downloads.
This runs only **identity, speed2, music_loop**, with these **10 controls**:

- Positive: `positive_reference_identity`, `positive_reference_speed2`,
  `music_reference`.
- Negative: `negative_unchanged_source_as_speed2` (duration),
  `negative_speed_changes_pitch` (pitch),
  `negative_frozen_video_correct_duration` (timecode),
  `negative_frozen_markers_advancing_barcode` (motion),
  `negative_audio_timestamp_gap` (PCM continuity), `music_unchanged` and
  `music_no_loop` (replacement/loop audio windows).

Negative controls must fail the existing required assertions; a decoder or
diagnostic-storage `IOException` is **ERROR**, never a successful rejection.
Other positive references/stress encodes and unrelated negative controls are
not decoded. The same immutable `EditConfig`, frozen fixtures/references,
`Media3ExportEngine`, `OracleVerifier`, identity signed YUV/RGB diagnostic and
speed/music PTS/EOS tracing are used, not another export pipeline.
Codec selection/capabilities, configure formats/rejections, decoder details,
color probes, hashes and tail traces remain in the incremental TXT.

Both TXT and `suite.json` explicitly retain mode, ordered planned case/control
lists and counts; JSON also enumerates omitted coverage. Short success is
**SUBSET_PASS**, with **FULL FROZEN SUITE: NOT RUN / UNVERIFIED**; 14 exports
and 50 controls were omitted. Failure, cancellation, interruption or incomplete
checkpoints are not passes. This is not proof that Samsung color or tail
behavior is fixed. A 601/709 signaling/conversion explanation remains plausible
but unproven; fresh physical-phone diagnostics and actual outputs are still needed.

“Short” reduces the inventory, **not** media duration or fixed timing limits.
Phone identity alone has taken about **139 seconds**; no fixed completion time
is promised. Existing 15-minute total / 180-second export / decoder budgets,
pins and thresholds are unchanged. Mode survives the real document picker and
activity recreation; active activity destruction still cancels, not resumes.
Both start entries are disabled during selection, restoration and execution.
The same SAF append/fsync, private committed journal, cancel/error and relaunch
recovery rules below apply to either mode.

New native `ShortDiagnosticTest` inventory: **8 tests** covering full/default
inventory, selected pinned controls, omission/count metadata, immutability,
subset-only success, selected-index error persistence, negative-control checked
I/O failure and cancellation. The existing **74 regression + 62 focused**
inventories are unchanged. Host report guards add four tests (20 total),
including rejection of a subset mislabeled with full counts and wrong/duplicate
selected identities. Real UI completion and recovery evidence is separate:

```powershell
python -B oracle_tools\run_report_crash_validation.py --serial emulator-5580 --short --complete-short --recreate-picker --evidence-dir app\build\short-final-ui
python -B oracle_tools\run_report_crash_validation.py --serial emulator-5580 --short --evidence-dir app\build\short-final-recovery
```

Run against matching committed/stamped APKs on the prepared API34 emulator.
These are actual DocumentsUI clicks, codec exports and durable files; screenshots,
hierarchies, terminal JSON and app-data archives are retained. Final revision,
APK/test hashes, exact observed results and any remaining blockers belong to
`app\build\short-diagnostic-progress.txt` and the delivery receipt, not to
predicted phone performance.

### Durable reports (both modes)

The screen shows the selected filename and exact SAF document URI (Android
does not expose a general filesystem path for all providers). For local Downloads,
find the TXT in **Files → Downloads**, even after the app process crashes.
Only local, seekable, fsync-capable providers are supported; provider, revoked-grant,
full-storage or permission failures are explicit and stop testing, never silently
substitute a private-only file. Pick local storage, not a cloud provider.

Before risky preparation, each control decode, export and verification, and
after meaningful results/codec progress, the app first appends/syncs the private
journal and atomically commits its byte offset, then **appends**, flushes, syncs
and closes the selected TXT. Recovery removes only an uncommitted private suffix;
old whole-file atomic reports and eight-byte offset markers (including AtomicFile
backups) remain readable. Starting a new saved report never clears the old journal:
the complete first checkpoint is written/fsynced into the inactive generation,
then its directory is synced. One combined generation/offset marker is written
and fsynced separately, atomically renamed over the old marker, and directory-synced.
Only then can the legacy data be retired or SAF mirroring begin. Thus interruption
before publication keeps the old report; after publication the complete new first
checkpoint is recoverable. A power loss around rename can select either complete
generation, never an old offset against a newly emptied file. An uncertain prior
rename is directory-synced before another generation slot is reused.

The journal's global lock covers recovery, append, publication and opening readers.
Inactive slots are unlinked, not truncated, so already-open readers retain their
generation even across repeated switches. Retention is two generation files plus
the tiny marker/staging marker; migration can temporarily retain the legacy file
until publication succeeds. No public TXT is deleted. Invalid committed offsets
still fail explicitly, rather than falling back to empty success. Private-phase
failure stops before external writes; external-phase failure retains the committed
private checkpoint and reports a sticky, explicit saving error. Earlier
checkpoints are not overwritten. Each has a timestamp and `END CHECKPOINT`;
an incomplete trailing checkpoint after a kill is not proof of completion.
Revision, installed APK hash, device, fixed input/config, case/stage, known
codec details and successful/failed checks are included. Catchable errors retain
stacks. Java uncaught-error recording is bounded **best effort**, chaining the
existing Android handler. Native crashes, OOM, force-stop and power loss may
prevent all final/uncaught handling: this records the last completed checkpoint,
not necessarily the cause of the phone's crash.

After relaunch, open **Run offline self-test** again: `RUNNING` / `IN PROGRESS`
means the previous run was **interrupted / UNVERIFIED**, not resumed or passed.
**打开已保存 TXT / Open saved TXT** streams the actual selected file off the UI
thread and displays only its explicitly labeled **16 Ki-character tail preview**
(no separate text viewer needed). Live updates are bounded and coalesced to one
pending callback; selectable TextView state never saves a full report. The callback
and publisher share only a short preview lock, never the worker's persistence
monitor. Snapshot replacement and clearing the pending flag are serialized on that
lock, so updates arriving during delivery schedule the next bounded callback.
Cancel, watchdog, codec/progress UI and suite ownership do not acquire storage locks.
Main-thread export lifecycle diagnostics use a separate FIFO with at most 16 pending
bounded messages; overflow is a checked suite failure, never blocking main or silently
dropping diagnostics. Completion follows queued persistence. Native codec checkpoints
remain synchronous on their emitting background thread. Cleanup retains ownership
until storage/codec work returns; responsiveness is not a claim that a stuck provider
can be forcibly interrupted.
**Copy report** requires confirmation that only the preview will be copied;
it never silently truncates a full report into the clipboard.
**Share report** shares the saved external TXT with an explicit Android chooser,
using a read-granted URI stream, never a giant String. Full diagnostics remain
on disk, not truncated or summarized by the preview limit. Share validation and
report restoration run off the UI thread,
without rerunning tests or uploading anything automatically. Missing external
files fail explicitly. Private snapshots are also restored for diagnostics;
they are not presented as the selected external file. Public selected TXTs are
not pruned by the app. Uninstalling removes private data/grants, not a Downloads
document; use Files to open it.

The c54b37c Samsung API35 run stopped after about 151.927 seconds during checker
controls, before any actual export RUN. It is not a full result. Its OOM stack
names `SavedReport.checkpoint` as an allocation failure point, not proof of a
unique leak: the old runner retained a cumulative builder, SavedReport retained
the previous full String, each checkpoint copied/encoded whole reports, and
queued UI callbacks retained additional full snapshots. Reporting now writes
deltas with fixed-size UTF-8 buffers, retains only the current event and bounded
preview, and never rebuilds the report in error cleanup. Storage errors remain
checked, sticky failures; unrelated runtime bugs are not hidden. No largeHeap,
forced GC, disabled diagnostics, skipped controls, tolerance changes or codec
matrix/EOS workarounds are used. Samsung color/tail questions remain separate
and require fresh physical-device evidence from the fixed candidate.

`ReportMemoryTest` exercises 512 ordered 64 KiB JSON events (over 32 MiB),
streamed checksums across runner/private/external files, blocked-main callback
coalescing, UTF-8 preview boundaries and legacy/partial-journal recovery.
Its separate **test-only** old snapshot-queue mutant deliberately exhausts the
normal bounded emulator app heap; production does not catch OOM as a remedy.
The real Downloads recovery harness also accepts `--large-report` to append a
clearly labeled 32 MiB emulator-only fixture after killing an interrupted run,
then verify persisted grants, bounded UI, explicit copy confirmation and full
URI sharing without rewriting the document. This fixture is not export evidence.

`ReportJournalTest` injects I/O failures at each generation/data/marker publication
boundary across whole-file, backup, offset, offset-backup and generation formats;
it checks old-or-complete-new recovery, append-tail rollback, stable open readers,
bounded retention and explicit corruption errors. `ReportJournalDeathTest` is a
host-selected kill/recover pair: ordinary discovery without host arguments logs
`NOT RUN` and performs no destructive operation; it is not process-death coverage.
Partial host arguments still fail explicitly. `journalOperation`,
`journalFormat` and `journalStage` select the boundary. Its native process-kill
matrix covers 55 replacement and 35 append interruptions with fresh-process recovery.
`ReportResponsivenessTest` blocks an actual Android proxy-file-descriptor write
against a disk-backed test provider, and separately a private-sync checkpoint,
while checking first/last preview delivery, main-loop cancel/watchdog responsiveness
and bounded diagnostic FIFO/error ordering. This uses the existing instrumentation
runner on API34; production remains Java8/API23 compatible.

No user video or download is needed. The suite:

- Validates the **fixed bundled** four-second 320×240, 24 fps AVC/AAC standard,
  independent analytic PNG expectations and pinned contracts. No runtime
  fixture generation, external media, network, Python, or PC transfer is needed.
- Runs the original 17 frozen encoded positive controls and 19 negative controls through
  the phone decoder/checker. Negatives include unchanged source for every
  nonidentity operation, wrong geometry/trim/gain/pitch, frozen frames,
  advancing timecode over frozen motion, and an audio timestamp gap.
  Negatives must produce an actual mismatch, never a decode/setup error.
  These controls test the checker, **not** the production export pipeline.
  Separate music (1 positive/2 negative), intro (1 positive/4 negative) and
  text (1 positive/3 negative) and generated-title (1 positive/7 negative) packs
   retain the original 56; PNG adds one positive and three negatives (omitted,
   misplaced, forced opaque), for **60 controls**, without changing existing pins.
- Sends 12 original frozen configurations plus music-loop, imported-intro, centered `OI` text
   and generated `OI` title cases (original 16 exports), plus a bounded PNG case
   (**17 exports**) through the **same Media3ExportEngine**
  used by the editor: identity, crop, 90/180/270° rotation, trim, resize, mute,
  quarter gain, half/double speed, and combined edits.
- Decodes actual exports on the phone: every frame's timestamp and binary
  frame/complement code, nine spatial probes against independent lossless
  expected frames, color/grid/moving markers, exact geometry, cadence/count/
  duration, and timestamped PCM tone order, pitch, RMS gain and continuity.
  Exported pixels are never used to generate expectations.

Frozen standard SHA-256:
`f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2`

Original manifest SHA-256:
`ed831215918936eeb13b95d0460f9d4d1ab889a928b815a3fdbe9fb7d8e513fe`

The original contract, manifest, reference checker and encoded controls are
bundled under `app\src\main\assets\video-oracle`; Android contract/code pins
also cover the precomputed PNGs. Frozen artifacts and numeric tolerances
must not be regenerated or relaxed to make a device pass.

Progress, Cancel, selectable details, Copy report, Share report, and exported
output previews are in the self-test screen. Detailed private reports and results persist in
`files/selftest/<run-id>/`; reopening loads the latest selected report snapshot
(`files/selftest/saved-report.txt`), or the latest private run for older installs.
Two private runs are
retained. `suite.json` is checkpointed with PASS/FAIL/ERROR/UNVERIFIED status,
expected/actual control outcomes, app revision/APK hash, device/OS and input
pins. Per-case JSON retains every numeric expected/actual assertion, candidate
hash and encoder/decoder formats/names. Human-readable summaries distinguish
controls from actual exports. Unrun cases remain UNVERIFIED after interruption.
Inspection has bounded loops and deadlines; the suite has a fifteen-minute
deadline (180 seconds/export, 30 seconds/decode). Cancellation may await native codec
cleanup. Leaving/destroying the screen cancels active work, not its saved
report. Sharing is explicit and user-directed, never automatic.

**A successful export alone is not a passed assertion.** Each case reports
PASS, FAIL, ERROR, or UNVERIFIED. ORACLE v1 + music-loop-1 + intro-concat-1 +
text-topology-1 + title-intro-1 + png-watermark-1 PASS requires all 17 exports
and all 60 checker controls (base 12/36 + music 1/3 + imported intro 1/5 +
text 1/4 + title 1/8 + PNG 1/4).
Full feature coverage deliberately remains **INCOMPLETE** because other text/generated
titles, intro letterboxing/edit/music combinations, arbitrary angles/source rotation metadata,
HDR, variable frame rate, stereo, exact overlay glyphs, perceptual quality,
sample-exact A/V synchronization, and native UI interaction coverage are not
fully verified. Engine assertions do **not** claim to tap or test the editor
UI. Codec incompatibility, timeout, cancellation, and failed inspection are
reported, never converted to success.

### Oracle decoder compatibility and phone evidence

The immutable Samsung SM-G991U1 Android 15/API 35 report
`D:\jfpx\apk\selftest-1789281321092.txt` (APK revision prefix `048ba82`)
shows 11 successful controls using `c2.qti.avc.decoder`, then an
`IllegalArgumentException` from native video decoder configure for
`positive_reference_combo`; no production exports had run. FFprobe confirms
the frozen reference and stress combo files are AVC High, 80×120, 48 fps.
A Qualcomm small-frame limitation is a **hypothesis**, not a measured Samsung
capability or confirmed root cause.

The independent oracle now enumerates named video decoders, checks advertised
size/full-format/flexible-YUV and requested color support, excludes aliases and
secure/tunneled-only candidates, and tries eligible hardware candidates before
explicit software fallback. Hardware with advertised full-format rejection stays
skipped. Only explicit software may attempt an unchanged format whose declared
level alone fails the capability check: a second capability query without level
must support the same dimensions, frame rate, profile, color and bitrate/feature
constraints. The query copies the format on API 29+ and reconstructs capability
inputs on API 23–28 without mutating the original; configure still receives its
original level, profile, CSD and size.
Evidence retains `format_supported=false`, the secondary query/result and
`level_advisory_fallback=true`; `eligible_attempt` is not a claim of full advertised
support. Each eligible named candidate gets at most one create/configure attempt,
within the existing deadline. Software decoding is allowed for this independent pixel check;
that earlier decoder fix did not change production export selection.
Before create/configure, synchronous report checkpoints retain candidate names,
dimensions, format and capability decisions. Narrow create/configure rejections
are recorded, allocated codecs released, and remaining eligible candidates tried.
Exhaustion is a contextual checked error: the case is ERROR, including negative
controls, and subsequent cases continue. Unrelated runtime bugs still surface.
No fixture, tolerance or requested size is changed to obtain a pass.
Native process crashes cannot be caught; only the last durable checkpoint may
survive. The existing private/SAF TXT append-and-sync policy is retained.

Encoder diagnostic storage failures retain their original checked `IOException`
in the worker's first-wins completion; a separate synchronous callback abort
cannot replace it. A narrow Media3 factory bridge routes that abort through
export failure/cancellation rather than leaking it from the GL callback.
Progress writes share the diagnostic publication lock and skip completed exports,
so a secondary storage error cannot race ahead of the original failure.
Missing durable diagnostics stop export, persist case/control `ERROR` (never an
expected-negative pass), and leave the private TXT/save-error UI available.
Unrelated runtime bugs remain runtime failures. Seven added native reporting
regressions cover real checkpoint write failure, worker cleanup, main-thread
responsiveness, callback runtime, duplicate completion and negative-control abort.
The focused inventory is now **62 tests** (previous 55 plus these seven);
the full suite remains **17 exports, 60 controls and 74 regressions**.
Final revision-specific evidence is recorded in the delivery receipt; this
storage fix does not resolve Samsung color or missing-tail behavior.

Focused tests are separate from the frozen 69-test inventory:
`com.simple.videoeditor.oracle.OracleVideoCodecSelectorTest` exercises bounded
injected level-only software eligibility, skip/release/retry/exhaustion and persistence-failure behavior;
`com.simple.videoeditor.PhoneCodecReportingTest` covers per-control ERROR and
continuation, runtime propagation, persistence, and native frozen combo/title decoding.
Emulator software-codec results cannot confirm Samsung OEM behavior; rerun on
the phone with a new writable SAF TXT to confirm the hardware-specific outcome.

On the existing API 34 AVD, fix revision
`12803723f1ac5c274c74f45543b0afc18115e4b6` passed all 22 focused native tests
(12 selector, five reporting/combo, five SavedReport). Both frozen combo controls
selected `c2.android.avc.decoder` with `software_fallback=true` and passed.
Focused evidence is in `app\build\phone-codec-focused-native.txt` and
`app\build\phone-codec-combo-native-logcat.txt`. The local schema adapter also
implements the observer contract: the existing producer/formatter regression
passed 378 guards and rejected the old decoder-key mutation. This is host
schema evidence, not a Samsung test. Final revision/APK/native results and
delivery/backup hashes are retained in `app\build\phone-codec-progress.txt`
and `app\build\phone-codec-delivery.json`; do not infer completion from a
running checkpoint. Lint retains the baseline 10 errors/153 warnings; the
unit-test task has no sources and is not runtime coverage.

### Samsung real-export follow-up

The later immutable `selftest-1789287678708.txt` (SHA256
`dc0a30d86b4a4a04c8c83f4709a22d989718d2413d1c2640e82c2ff62dcbfbfa`)
completed 56/56 controls but **0/16 verified exports**: 14 FAIL and two encoder
configure ERRORs. Thirteen outputs exceeded the unchanged ROI color limit;
`speed2` additionally had 94/96 frames, and `music_loop` had 92/96 frames and
3.833333/4 seconds. Its zero *decoded* frames resulted from the metadata gate,
not an empty produced video. This report does not establish a native crash.

SDR AVC export now enumerates named encoders deterministically, checks surface
input and the exact coded dimensions/frame rate, then checks bitrate mode,
bitrate and profile/level support before configuration. Resize remains 160×120;
the rotated combo is encoded at 120×80, not inflated to a hardware minimum.
Expected native initialization/unsupported-format rejection advances to the
next supported configuration, including software encoders; exhaustion is an
error. No post-export verification failure is converted into success.
Inherited source codecs/CSD and optional operating-rate/priority hints are not
copied into the new encoder configuration. Feasible AVC levels replace the
previous highest-level selection; Media3's device-aware bitrate policy and
legacy API/device B-frame safeguards remain. These compatibility changes do
**not** identify which original QTI field was rejected.

Public editing and self-test exports use the same engine. Durable SAF TXT now
captures grouped capabilities, full formats **before configure**, rejection
causes, selected codecs, configure durations and export/release timings.
Speed/music exports additionally retain bounded Media3 first/last PTS and EOS
traces per pass, including decoder, GL/effect, encoder and muxer events.
Repeated codec-output polls are not sample counts. Progress resets per export.
No speculative tail-frame duplication, timestamp repair or transmux change
has been made: the phone's missing-tail boundary remains unlocalized.

After ordinary identity verification is saved, an independent diagnostic
decodes that same output and the pinned source using default and software-only
selection. It retains hashes, metadata, bounded SPS/VUI bytes, image layout,
first-probe raw Y/Cb/Cr means, metadata-consistent Kr/Kb RGB, oracle RGB and
frozen expected RGB for every sampled region. This is **evidence, not a new
verdict**: unavailable comparisons are explicit, no alternate matrix is chosen
to pass, and all original checks/pins/tolerances and PNG functionality remain.
Agreement on buffer decoding cannot exonerate source surface/external-texture
or GL processing. BT601-to-BT709 conversion alone is not evidence of a defect.
Pinned Media3 1.5.1 `DefaultVideoFrameProcessor.Factory.Builder` selects
`WORKING_COLOR_SPACE_DEFAULT`. Its SDR external shader samples RGB through
`samplerExternalOES` (the platform performs YUV sampling/conversion), rather
than applying the oracle's explicit YUV coefficients. With default working
space and SDR output, the shader passes those electrical RGB values through
the effect chain; the encoder surface then converts/signals the output.
The explicit external YUV matrix in `DefaultShaderProgram` is an HDR path,
not this SDR path. Therefore matching output metadata alone cannot establish
the source-surface or encoder-surface conversion actually used on Qualcomm.
The diagnostic is bounded to four first-frame passes (30-second cooperative
budget); native calls and durable I/O cannot be forcibly timed out safely.

New native tests cover exact-size selection/rejection injection, real surface
encoding and sample tails at both failing sizes, and independently FFmpeg-
encoded BT709 versus frozen RGB. Mocks establish fallback policy only.
Validation and delivery receipts: `app\build\samsung-export-progress.txt` and
`app\build\samsung-delivery.json`. **Samsung device-specific configuration,
color and tail correctness remain unverified**; inspect a fresh durable phone
TXT and retain its actual MP4s before attributing or correcting those failures.

## Why the backend changed

The old optimized path configured its decoder without a rendering surface
and never rendered decoded frames onto the encoder input surface. Its text
argument was unused, speed changed a frame-rate setting rather than media
timing, and video processing did not retain audio. Resize was incorrectly
described as crop. Fast mode ignored edits; later audio failures could be
reported as success, and trim/volume waiting loops could wait indefinitely.

The active editor now exclusively uses validated immutable `EditConfig` and
Media3's decoded-frame/effect/audio pipeline, with explicit error/cancellation
handling. Legacy helper classes remain in the source tree but are not used
by the editor or self-test. Requested resolution fallback is disabled:
unsupported codec dimensions fail rather than silently resize.

## Build and validation

All watermark manifest pins use committed LF bytes. The generator writes LF
explicitly and Git attributes preserve the whole watermark asset pack; automatic
Windows newline conversion must not change an integrity-checked input.

Generated-title density normalization is not a substitute for physical-pixel
inspection: the checker also inspects every original ROI pixel outside its
density-scaled ink/fringe mask. This rejects corruption between downsampling
rows; the local density regressions include unsampled white stripes at 2x/3x/4x.

Text backing probes adapt their edge inset to the declared 4–13 pixel glyph-gap
range. Previously a fixed three-pixel inset produced no samples for gaps 4/5
and falsely failed otherwise valid text. `TextParityMain` covers every allowed
gap with correct and absent backing, without relaxing the color tolerance.

Use the existing JDK 17, local Android SDK platform 35 (`.local-sdk`),
AGP 8.6.1 and Gradle 8.7 wrapper/cache. No tool installation, SDK download,
emulator setup or reboot is needed. The following are reproduction commands,
not actions performed for this documentation update. From the project root,
build offline only when the sources being attributed are actually committed:

```powershell
Set-Location D:\jfpx\simple-video-editor
$env:ANDROID_HOME = (Resolve-Path .\.local-sdk).Path
$changes = git status --porcelain --untracked-files=normal
if ($LASTEXITCODE -ne 0 -or $changes) {
    throw 'Do not attribute pending or untracked sources to a committed revision.'
}
$revision = (git rev-parse --verify HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -cnotmatch '^[0-9a-f]{40}$') {
    throw 'A full actual committed SHA is required.'
}
$env:VIDEO_EDITOR_REVISION = $revision
.\gradlew.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest
# Existing optional host checks (not native validation):
.\gradlew.bat --offline :app:testDebugUnitTest :app:lintDebug
```

Do not stamp a dirty build with HEAD and call it committed-source evidence.
For exploratory dirty builds, unset `VIDEO_EDITOR_REVISION` and retain the
unverified label; the runner's terminal-suite revision check will not accept it.
Its SHA-format check alone cannot establish clean provenance. Compare the
captured source revision and installed APK hash with the committed build and
delivery receipt. Unrelated untracked documentation/scripts may be preserved,
but any untracked build inputs must be reviewed before attributing a build.

APK: `app\build\outputs\apk\debug\app-debug.apk`.
Version: `1.2-media3-oracle` (code 3). Without `VIDEO_EDITOR_REVISION`, reports
explicitly label the source revision unverified; the installed APK is always
hashed. Debug signing differs from the older CI build. Do not casually uninstall:
an incompatible-signature update needs a matching signer or a deliberate
backup/reinstall plan. Uninstalling deletes app-owned media and reports.
There are currently no repository unit-test sources: a NO-SOURCE unit-test
task is **not** evidence of runtime coverage. Lint still reports pre-existing
legacy codec flag annotations. The touched template manager now uses API23-safe
iteration instead of `removeIf`; unrelated legacy codec errors remain separate
from the new engine.

Local parity runs the pure Java checker on independently FFmpeg-decoded media:
17 positives accepted, 19 negatives rejected, all 36 full/sparse reports equal,
and shared assertion outcomes match the frozen Python reference. This is
**local checker evidence only**, not Android color/codec/export validation.
Reproduce using the original frozen oracle directory (do not run `generate`):

```powershell
python -B oracle_tools\run_video_oracle_parity.py --oracle-root $oracle
python -B oracle_tools\test_video_oracle_tools.py --oracle-root $oracle -v
python -B oracle_tools\package_phone_controls.py --help
python -B oracle_tools\run_schema_regression.py --oracle-root $oracle --check-old-typo
python -B oracle_tools\test_pitch_preserving_audio_processor.py
python -B oracle_tools\test_emulator_validation.py
python -B oracle_tools\test_watermark_oracle.py
```

The PNG oracle uses an original synthetic 64×32 RGBA image with alpha 0/128/255,
20% width and free-space X/Y 75% on a 320×240 canvas: rectangle
(192,156)–(256,188). Its independent numeric straight-alpha reference uses
the original analytic backgrounds, never production-rendered expectations.
Nine probes check five alpha/color regions; original temporal/audio checks
remain enabled. Frozen assets/tolerances must not be regenerated to obtain a pass.
`WatermarkOracleTest` adds five native tests (74 regression tests total),
including bounded decoding, cache/config isolation and a real production export.
The existing separate decoder/SAF regression inventory remains unchanged.
`oracle_tools\run_watermark_ui_validation.py --serial emulator-5580
--evidence-dir app\build\watermark-ui-final` exercises actual DocumentsUI picks,
width/X/Y input, screenshots/pixel checks, Process export and clear; it then
independently decodes the actual export using compiled `WatermarkParityMain`.
This UI witness is separate from the self-test, which does not automate UI.
Final revision, APK/signing hashes and native/UI evidence are recorded in
`app\build\watermark-delivery.json` and `D:\jfpx\apk\delivery-receipt.txt`
only after validation. No physical Samsung validation is implied.

The runner and verifier share the `decoder` report key. A former `decode`
consumer typo aborted the suite after its first export. The schema regression
executes the actual producer and production summary formatter for all 12 cases
using local decoding substitutes, and rejects the old typo as a mutation.
This guards the report interface, not Android export/runtime correctness.

The supplementary `music-loop-1` pack retains the original frozen video and
analytic pixel expectations. A pinned one-second 1320 Hz, peak-0.16 PCM tone
must replace the original soundtrack and repeat across all four seconds.
The same core checks video content/timecodes, audio frequency/RMS and continuity.
Its independent FFmpeg reference must pass; unchanged original audio and
one-second music followed by silence must fail audio assertions. These three
controls run on the phone before exports. Reproduction source is
`oracle_tools\build_music_oracle.py`; frozen shipped hashes must not be changed
merely to accommodate a device. `MusicParityMain` runs local decoded controls;
`MusicOracleTest` exercises Android decoding and the production report formatter.
This does not cover arbitrary music formats, audible loop-boundary clicks,
or combinations with speed and intros.

The separate pinned `intro-concat-1` supplement imports standard frames 48–71
(one second, 880 Hz), then appends the complete original frames 0–95
(440/660/880/1100 Hz): five seconds, 120 frames. The default suite exports this
through Media3, then independently checks every mapped timecode, analytic pixels
on both sides of the join, fifteen PCM tone/gain windows and timestamp continuity.
Expected pixels remain the original frozen analytic PNGs; the positive reference
and four negatives are independently encoded by FFmpeg, never by Media3.
Missing intro, reversed order, wrong duration and silenced original audio must
fail their specified assertions, not merely decode unsuccessfully.

Supplement manifest SHA-256:
`d669b72ff77ac9a6915f3e34d08a33685bddb4da47714cfd303bbe0afe456032`

All input/control hashes, version, config, expected/actual measurements and
global planned counts persist in phone reports. Original base/music pins are
unchanged. `oracle_tools\build_intro_oracle.py` verifies the shipped pack by
default; `--help` describes explicit offline reproduction. After compiling
`oracle_tools\LocalParityMain.java`, `oracle_tools\IntroParityMain.java` and the
pure-Java contract/core classes, run (JDK 17, FFmpeg on PATH):

```powershell
$java = "app\src\main\java\com\simple\videoeditor\oracle"
javac -encoding UTF-8 --release 8 -d app\build\intro-parity\classes `
  "$java\OracleContract.java" "$java\OracleGeneratedContract.java" `
  "$java\OracleCoreVerifier.java" "$java\OraclePcmUtils.java" "$java\IntroOracleContract.java" `
  "$java\TextOracleContract.java" "$java\TextOracleVerifier.java" `
  "$java\TitleOracleContract.java" "$java\TitleOracleVerifier.java" `
  oracle_tools\LocalParityMain.java oracle_tools\IntroParityMain.java
java -cp app\build\intro-parity\classes com.simple.videoeditor.oracle.IntroParityMain --require-core
```

Local evidence: all five whole-file full/sparse controls agree, including eight
join-alignment mutation checks. This is not native export evidence.
Letterboxing and combined-intro coverage remain pending.

The TEXT-only `text-topology-1` supplement checks the fixed string `OI`: one
closed O ring followed by a solid narrow I stem, white color, fixed size/spacing,
centered ink and 60%-black backing at nine probes. Expectations are original
analytic ellipse/rectangle geometry, not copied fonts or production-rendered
pixels. This is bounded topology, not general OCR: confusable glyphs and exact
outlines are not distinguished. No new fonts or dependencies are used.

Only the fixed rectangle `[130,190) x [84,158)` is excluded from base image
comparison; overlapping region/marker probes are omitted for this case only.
Surrounding pixels, horizontal motion, audio, every frame timestamp and visible
barcode bits 0/1/5/6 retain the original tolerances. Covered bits/markers are not
claimed as checked. Four pinned encoded controls cover reference, reversed `IO`,
no text, and a six-pixel horizontal misplacement. All non-text checks must pass
even for negatives. The recovered pre-commit 70px misplacement obscured retained
barcode bits; only that control and its manifest pins were corrected. The other
three encoded controls and all base/music/intro assets remain byte-identical.

The existing engine is unchanged: `AbsoluteSizeSpan(48)` overrides Media3 1.5.1
`TextOverlay`'s initial 100px paint during `StaticLayout` measurement/drawing.
Inspection of the cached pinned AAR confirms bitmap/video dimension-ratio
scaling and default scale 1, anchors (0,0): bitmap pixels map 1:1 onto the
320x240 output, centered. Fixed O bounds 25..34 by 31..39 and I bounds 3..8 by
31..39 are plausible for a 48px default sans-serif em, not measured native font
results. OEM font metrics, hinting, layout padding, blending and codec output
remain uncertain; out-of-bounds native output must fail, never trigger fitting.

```powershell
python -B oracle_tools\build_text_oracle.py # verify shipped pins; no regeneration
$java = "app\src\main\java\com\simple\videoeditor\oracle"
New-Item -ItemType Directory -Force app\build\text-parity\classes | Out-Null
javac -encoding UTF-8 --release 8 -d app\build\text-parity\classes `
  "$java\OracleContract.java" "$java\OracleGeneratedContract.java" `
  "$java\OracleCoreVerifier.java" "$java\OraclePcmUtils.java" "$java\IntroOracleContract.java" `
  "$java\TextOracleContract.java" "$java\TextOracleVerifier.java" `
  "$java\TitleOracleContract.java" "$java\TitleOracleVerifier.java" `
  oracle_tools\LocalParityMain.java oracle_tools\TextParityMain.java
java -cp app\build\text-parity\classes com.simple.videoeditor.oracle.TextParityMain
```

Local TEXT evidence: four full/sparse controls agree; filled-hole, red-text,
background, frozen-motion and silent-audio mutations fail meaningful assertions.
Metadata-only duration rejection does not require an absent `video.frame_count`.
Base 36, music 3 and intro 5 Java controls also pass. The current producer/formatter
schema regression covers TEXT, TITLE and default 16/56 counts, including rejection of
the old `decode` key mutation. The recovered `app\build\title-frozen-root`
now supports all 13 existing Python tool tests. Direct Java control evidence
and these tool tests are not a fresh full Python-reference parity run.

Duration-negative phone controls require the metadata `video.duration` failure,
not a decoded frame count: Android intentionally skips video decoding when the
duration is already wrong. Local parity also exercises empty-frame metadata
rejection so an unavailable assertion cannot prevent a correct negative result.
The frozen manifest retains its offline fully decoded frame-count measurements;
the phone's early-rejection requirement is deliberately narrower. Git attributes
disable text conversion for all oracle asset packs: Windows CRLF checkout must
not change a byte-pinned manifest.

### Bounded generated title: `title-intro-1`

One additional default export uses the unchanged production generated-title path:
`IntroTemplate` → immutable `EditConfig.IntroTitle` → engine-rendered PNG →
Media3 image composition. Configuration is fixed: centered white `OI`, **48sp,
normal**, opaque black background, 1000ms, then the complete original standard.
No imported title video, user media, candidate/reference substitution or editor
font change is involved.

Unlike the 48px TEXT overlay, production title Paint uses `48 * scaledDensity`
pixels. The oracle independently normalizes inspected coordinates by the known
display density, not detected glyph bounds. Supported density is explicitly
0.75–4; others produce an error, not a calibrated tolerance or false pass.
Local analytic geometry checks cover .75/1/1.5/2/3/4; OEM font metrics remain
unverified and can legitimately fail the fixed bounds.

All 30 title frames are checked for O-ring/I-stem topology, size, placement,
white/black color, fixed empty interior bands, black holes/background outside a
two-pixel antialias fringe, and static normalized-ROI MAE≤2.
Expected cadence is precisely 30fps for one second, then 24fps for four seconds
(126 frames). The join, original main timecodes, frozen spatial/moving-marker
probes, three silent-title PCM windows, twelve original tone/RMS windows and
whole-file PCM continuity are checked. This is finite topology/sampling, not
general OCR, exact font outlines or sample-exact A/V synchronization.

The separate byte-pinned `title-oracle` pack uses original analytic ellipses and
rectangles plus FFmpeg, never Android rendering. Its eight phone controls cover
reference, missing glyphs, reversed `IO`, wrong duration, reversed segment order,
audible title, muted original audio and wrong background. Duration rejection
requires `video.duration`, not unavailable decoded frame counts. All prior asset
packs remain byte-identical.

```powershell
python -B oracle_tools\build_title_oracle.py # verify shipped pins, never regenerate
$java = "app\src\main\java\com\simple\videoeditor\oracle"
New-Item -ItemType Directory -Force app\build\title-parity\classes | Out-Null
javac -encoding UTF-8 --release 8 -d app\build\title-parity\classes `
  "$java\OracleContract.java" "$java\OracleGeneratedContract.java" `
  "$java\OracleCoreVerifier.java" "$java\OraclePcmUtils.java" `
  "$java\IntroOracleContract.java" "$java\TextOracleContract.java" `
  "$java\TextOracleVerifier.java" "$java\TitleOracleContract.java" `
  "$java\TitleOracleVerifier.java" oracle_tools\LocalParityMain.java oracle_tools\TitleParityMain.java
java -cp app\build\title-parity\classes com.simple.videoeditor.oracle.TitleParityMain
```

Local evidence: eight full/sparse encoded controls agree; omitted-title metadata,
static-content, interior-background, density and main-motion mutations reject.
Base 36, music 3, imported intro 5 and TEXT 4 core regressions pass. Producer/
formatter schema regression checks 378 guards, the 16/56 default counts and
rejects the old `decode` typo. The recovered frozen controls were copied
byte-for-byte from bundled assets. The recovered `app\build\title-frozen-root`
passes 13/13 existing tool tests, including inventory checks; no frozen
control changes are required. These are **local checker** results, not native
export results.

### Native runtime corrections

Real emulator investigation identified and corrected the following issues:

- Media3 1.5.1 Sonic's integer AMDF period-selection arithmetic can overflow
  at ordinary PCM levels, selecting wrong periods and disturbing pitch/RMS
  during speed changes. `PitchPreservingAudioProcessor` uses wide AMDF sums
  and cross-products with streaming period insertion/removal and crossfades.
  The existing host-only `test_pitch_preserving_audio_processor.py` exercises
  this processor using cached Media3/Guava and the local SDK, without Gradle
  or downloads; it is not native codec evidence.
- Speed changes need encoder frame-rate/quality budgeting for the actual
  output cadence, not just the source nominal rate. The engine probes rates,
  budgets main rate times speed and the maximum imported-intro/title cadence,
  and uses Media3's codec-aware quality policy. This does not replace actual
  timestamp-based speed processing or force intros to run at main speed.
- Generated-title silence must delay main audio rather than let it start
  under the title. The engine creates a PCM WAV gap of the title duration
  matching the probed source sample rate and channel count, then sequences
  edited main audio after it. It does not assume a fixed mono audio format
  or invent a source track when none exists.
- `OracleAndroidDecoder` accounts for Android gapless AAC delay/padding:
  SkipCutBuffer can shorten the first PCM buffer and carry samples into
  later buffers without adjusting their PTS. Correction is restricted to the
  recognized metadata, sample-count and input/output timestamp pattern.
  Raw timelines remain diagnostic evidence; it never fills missing PCM or
  flattens arbitrary gaps/overlaps to make continuity pass.
- Duration-negative controls can fail at metadata inspection before video
  decoding. Their native checks require `video.duration`, not an unavailable
  decoded `video.frame_count`; missing assertions are not successful checks.

These correct implementation/checker assumptions, not the frozen oracle:
numeric tolerances, pinned controls, manifests and analytic expectations
must remain unchanged. The existing offline parity/schema/control commands
above remain relevant. `build_music_oracle.py` is a generator, not a verifier:
do not run it to validate existing controls. Verify shipped asset hashes instead.

### Repeatable emulator validation

#### Crash-report persistence validation

The reporting fix adds a separate focused workflow; the 16-export/56-control
oracle and frozen 69-regression inventory are unchanged. Build/install matching
app and androidTest APKs first, then on the isolated API 34 AOSP emulator:

```powershell
.\.local-sdk\platform-tools\adb.exe -s emulator-5580 shell am instrument -w -r `
  -e class com.simple.videoeditor.SavedReportTest `
  com.simple.videoeditor.test/android.test.InstrumentationTestRunner
python -B oracle_tools\run_report_crash_validation.py --serial emulator-5580 `
  --evidence-dir app\build\crash-report-validation\unique-run
```

Five native tests use a **wrapped fake disk/pipe provider** to test append-only
checkpoints, private-first saving on provider failure, non-seekable rejection,
best-effort Java error recording and report ownership. They do **not** simulate
persistable SAF permission grants. The separate Python scenario taps the
**real DocumentsUI Downloads Save dialog**, waits for a successful native control
and a subsequent risky stage, records the app PID, force-stops the app without
Java cleanup, and checks that the external TXT survives. A sixth native test
(`SavedReportRecoveryTest`, invoked only by that scenario) checks the persisted
read/write grant and real document after process death. The scenario then reopens,
copies and opens the Android share chooser without rerunning the suite or
selecting a recipient. XML hierarchies, screenshots, pre/post-kill TXT, native
output and a SHA-256 summary are retained in the chosen evidence directory.
This proves process-death persistence on that emulator, not OEM codec/OOM crash
causation or all third-party document providers. API 23–28 SAF uses the same
production APIs but has not been exercised on an older emulator.

Validation uses the official API 34 AOSP x86_64 emulator with WHPX at explicit
serial **`emulator-5580`**. Its isolated emulator home is
`C:\owned-evidence\oracle-validation` because D: is low on space;
the SDK remains project-local. During recovery the existing online instance
was reused without setup, reboot or virtual-machine changes. Do not overlap
runs or restart an active instance. After the owned emulator is stopped at
delivery, a future validation session can start the same existing AVD
(`copilot-validation-api34`) without reinstalling tools or recreating it:

```powershell
Set-Location D:\jfpx\simple-video-editor
$env:ANDROID_HOME = (Resolve-Path .\.local-sdk).Path
$env:ANDROID_USER_HOME = "$PWD\app\build\emulator-validation\android-user"
$env:ANDROID_AVD_HOME = 'C:\owned-evidence\oracle-validation'
# Only when emulator-5580 is not already running; retain the returned owned PID.
$emulator = Start-Process .\.local-sdk\emulator\emulator.exe -PassThru `
    -ArgumentList '-avd copilot-validation-api34 -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -cores 2 -accel on -partition-size 768 -camera-back none -camera-front none -no-metrics'
.\.local-sdk\platform-tools\adb.exe -s emulator-5580 shell getprop sys.boot_completed
# Wait for output 1 before running tests. Never kill a shared adb server.
```

`oracle_tools\run_emulator_validation.py` uses existing Python and
`.local-sdk\platform-tools\adb.exe`. It verifies the selected device is a
booted emulator, installs both existing matching APKs with `install -r`,
and targets every ADB operation with the supplied serial. It does not build
APKs or boot an emulator. Preserve the signing/backup precautions above.

| `--stage` | Existing native work | Instrumentation deadline |
| --- | --- | --- |
| `smoke` | Production-engine rotate-90 export via `EmulatorValidationTest` | 300s |
| `suite` | Production `SelfTestRunner` and fresh terminal `suite.json` | 1100s |
| `regressions` | Seven instrumentation classes listed below | 1800s |
| `all` (default) | Smoke, suite, regressions sequentially | Each stage's bound |

The runner requires exactly 69 regression test identities. Regressions are `OracleVerifierTest`, `MusicCompositionTest`,
`IntroCompositionTest`, `MusicOracleTest`, `IntroOracleTest`, `TextOracleTest`
and `TitleOracleTest`. Stage failures are retained; `all` continues to later
stages unless a runner error interrupts it. Every subprocess has a finite
timeout; installs allow 300s each and artifact capture has separate bounds.
On instrumentation timeout the runner kills its owned ADB client and
force-stops this app on the explicit emulator, not other devices.

For a later run, choose one stage below. This detached Windows example keeps
stdout, stderr, PID and evidence inside the project, not a temporary directory.
`Start-Process` has no `-Wait` or `-NoNewWindow`; the Python process remains
independent of the invoking shell. Do not launch it while validation is active:

```powershell
Set-Location D:\jfpx\simple-video-editor
$stage = 'all' # alternatively 'smoke', 'suite', or 'regressions'
$root = (Get-Location).Path
$run = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') +
    '-' + [guid]::NewGuid().ToString('N')
$base = 'app\build\emulator-validation'
$logs = "$base\launcher-$run"
$evidence = "$base\run-$run" # must not already exist; runner creates it
New-Item -ItemType Directory -Path $logs | Out-Null
$python = (Get-Command python.exe -CommandType Application -ErrorAction Stop).Source
$arguments = '-B -u oracle_tools\run_emulator_validation.py ' +
    "--serial emulator-5580 --stage $stage " +
    "--adb .local-sdk\platform-tools\adb.exe --evidence-dir $evidence"
$process = Start-Process -FilePath $python -ArgumentList $arguments `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $root "$logs\runner.stdout.txt") `
    -RedirectStandardError (Join-Path $root "$logs\runner.stderr.txt")
$process.Id | Set-Content "$logs\runner.pid"
# Read-only monitoring; a live PID/heartbeat is not a passing result:
Get-Process -Id ([int](Get-Content "$logs\runner.pid")) -ErrorAction SilentlyContinue
Get-Content "$base\progress.txt" -Tail 30
Get-Content "$evidence\native-results.json"
```

The shared append-only `app\build\emulator-validation\progress.txt` records
UTC time, runner PID, evidence path, stage start/end, child PID and a heartbeat
every 30s while waiting on subprocesses. Correlate entries by PID/evidence;
outer redirected stdout can be quiet while stage output goes directly to disk.
Each unique evidence directory retains:

- `<stage>.stdout.txt` / `<stage>.stderr.txt`, bounded command logs,
  `<stage>-logcat.txt`, and `<stage>-app-data.tar` for available app reports.
  Regression-only runs may have no app-data archive when no report dirs exist.
- Device properties/codec diagnostics, APK hashes and installation outcomes.
- `terminal-suite.json`, copied from the one fresh suite in
  `suite-app-data.tar`, not an older retained run.
- Atomically checkpointed `summary.json` and `native-results.json`, including
  per-test status counts, artifact hashes/errors and terminal-suite details.

The suite gate requires a complete, uncancelled, successful terminal report,
all **16 exports and 56 controls** with expected outcomes (21 positive,
35 correctly rejected negative controls), plus a full source-revision SHA.
The runner also requires the legacy runner's successful terminal
`INSTRUMENTATION_CODE: -1`, not merely individual passing status codes.
Capture errors, incomplete inventories, timeouts, RUNNING/UNVERIFIED reports
and interrupted runs are not passes.

Observed recovery run `app\build\emulator-validation\resume-revision-1`:
**1/1 smoke, 16/16 production exports, 56/56 checker controls and 69/69
regressions passed** on committed source
`ad7c68311ad2f953a15b1d2367e8e92836496f8a`, installed APK SHA-256
`7eedebe7b0c930d4cb29e790e9062fae02be5cefe8afd22321fa12ecd459747f`.
The controls include 21 positives and 35 meaningfully rejected negatives.
Independent FFmpeg decoding/ffprobe inspected all 16 MP4s and checked 57
audio windows across identity, half/double speed, combo and generated title.
Double-speed 1100Hz windows measured 1099.995–1100.014Hz and RMS
0.22553–0.22568 (previous faulty export approximately 1079Hz/0.192).
Generated title retained one second of silence followed by main audio;
its 126 video frames comprise 30 title frames plus 96 original frames.
AAC decoded sample duration and track metadata can differ by encoder padding;
neither a successful mux nor padded PCM length alone establishes correctness.
Host processor regressions passed 5,130 streams; all 13 oracle-tool tests and
producer/consumer schema guards passed. Lint retains 10 unrelated legacy
errors; Gradle unit tests have NO-SOURCE and are not counted as tests.

The delivery revision is rebuilt and installed together with its matching
test APK for a fresh full run after documentation/harness finalization.
Use `D:\jfpx\apk\delivery-receipt.txt` and the referenced ignored evidence
directory for that final commit, APK/test hashes, exact counts, signer,
backup, independent analysis and cleanup. Do not substitute the earlier run
above as proof for an APK with a different hash.

This exercises Android Media3, software emulator AVC/AAC codecs and the EGL
rendering path, not just host substitutes. It does not establish OEM hardware
codec behavior, HDR/color behavior across devices, stereo fidelity, arbitrary
media, native editor UI interactions or perceptual quality. Neither offline
host checks nor the bundled offline suite replaces physical OEM validation.
Observed encoders were `c2.android.avc.encoder` / `c2.android.aac.encoder`,
decoders `c2.goldfish.h264.decoder` / `c2.android.aac.decoder`, and renderer
Google SwiftShader through the Android Emulator OpenGL ES 3.0 translator.

Ordinary exports have no app elapsed/stall timeout; encoder support varies by
device. Vendor native calls cannot be forcibly interrupted; a stalled call
may require stopping the app. Retained in-progress reports are not passes.

Version/API references:
[Media3 1.5.1 source and release notes](https://github.com/androidx/media/tree/1.5.1),
[Transformer guide](https://developer.android.com/media/media3/transformer/getting-started),
[AGP 8.6 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-6-0-release-notes).
The pinned release's source/API and published AAR requirements, rather than
newer examples in the rolling guide, determine this implementation.

## 中文操作摘要

本版本使用 Media3，不再使用旧的空帧导出路径。先选择视频，再设置裁剪保留区域
（左/上/右/下百分比，默认 0/0/100/100）、旋转、截取、输出高度、文字、速度与音量。
缩略图对应先裁剪后旋转；它不是音频或全部特效的实时预览。默认保留原音，0% 静音。
快速模式已停用；静态模板片头、导入片头和循环替换音乐已使用 Media3 组合导出，
目前已在准备好的 API 34 模拟器上进行原生回归验证，尚无最终通过结论。

安装后点击离线自检入口，再点 Start suite，无需提供视频或电脑传输。程序使用固定内置
标准视频、独立预期图像与音频公式，运行 56 个正负校验对照及 16 个正式引擎导出，
在手机上解码比较实际输出，可取消、复制/分享报告及预览结果。
报告会保留未覆盖项目，不能把 INCOMPLETE 当作全部通过。现有 emulator-5580 已启动，
原生验证仍在进行，负责人将在完成后补充真实最终计数；不代表原生界面或 OEM 设备已验证。
本次仅更新文档，不安装、启动或重启模拟器。文件保存在应用私有空间，卸载会删除。

## License and privacy

MIT. No ads, tracking, subscriptions, or automatic uploads. All media
processing and inspection happen locally.

## Clean-history root note

This locally prepared repository contains one fresh root commit of the latest verified public source, preserving the complete project and its pinned public-baseline fixtures without importing prior Git ancestry. Only this root note is adjusted during this preparation. App, Android test, resource, Gradle and media inputs remain byte-identical to the verified direct-video-uri candidate commit b0bb78314113349cbd08a362bc5f4fbc040b7b7d; host privacy tools, their tests and CI include the subsequent public-baseline and bounded-Qt fixes described below.

Builds must set `VIDEO_EDITOR_REVISION` to the selected checkout's full commit hash; CI uses its current checkout revision, not a hardcoded former main commit. Historical revision identifiers in the immutable public-baseline fixture describe provenance, not required Git ancestors. The previously verified private APK SHA-256 f66a245a5d0a080d01283b630b778d63032c181a1fb55e7a76524b0a002d2b39 was built from b0bb78314113349cbd08a362bc5f4fbc040b7b7d, not this fresh root. A newly stamped APK is deferred until parent review; local source preparation is not publication authorization.

Public exporter checks now use the pinned, readable `oracle_tools/public_baseline.json`,
not a private ancestor or the current checkout as authority. Its sanitized reference
inputs preserve numeric assertions, inventory and hash dependencies; genuine historical
derivation evidence remains private. Run `python -B -m unittest discover -s oracle_tools
-p test_public_baseline.py` and `python -B oracle_tools/privacy_export.py --receipt
equivalence.json`. Verification is read-only; changing the authority requires review.

The subsequent publication fix changes only host privacy tools, their tests, CI and
these notes; it does not change app, resource, Gradle or media inputs. The privacy
gate also decodes bounded Qt qCompress music-project payloads inside evidence ZIPs.
The earlier APK remains evidence for its original revision, not a build of this fix.
