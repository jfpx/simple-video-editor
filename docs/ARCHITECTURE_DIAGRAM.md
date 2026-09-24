# Background Music Feature - Architecture Diagram

## Component Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          MainActivity.java                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  User Interface Layer                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐ │
│  │ btnSelectVideo   │  │ btnSelectIntro   │  │ btnSelectMusic  │ │
│  │ (Main Video)     │  │ (Optional)       │  │ (Optional)      │ │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬────────┘ │
│           │                     │                      │           │
│           ▼                     ▼                      ▼           │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │            File Selection & Cache Management                 │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │ • openVideoPicker()    → REQUEST_CODE_VIDEO (1000)          │  │
│  │ • openIntroPicker()    → REQUEST_CODE_INTRO (1001)          │  │
│  │ • openMusicPicker()    → REQUEST_CODE_MUSIC (1002) ★NEW★    │  │
│  │                                                               │  │
│  │ Cache Files (getCacheDir()):                                 │  │
│  │ • input_video.mp4          [Main video]                      │  │
│  │ • intro_video.mp4          [Intro, if selected]              │  │
│  │ • background_music.<ext>   [Music, if selected] ★NEW★        │  │
│  │ • temp_processed_*.mp4     [Temporary, auto-deleted] ★NEW★   │  │
│  └─────────────────────────────────────────────────────────────┘  │
│           │                     │                      │           │
│           └─────────────────────┴──────────────────────┘           │
│                                 │                                  │
│                                 ▼                                  │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │                   Processing Logic                           │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │  processVideo() {                                            │  │
│  │                                                               │  │
│  │    if (isFastMode) {                                         │  │
│  │      // Simple rotation only (no music support)              │  │
│  │      VideoProcessorOptimized.fastRotate(...)                │  │
│  │    }                                                          │  │
│  │    else {                                                     │  │
│  │      // Full Mode - Complex processing                       │  │
│  │                                                               │  │
│  │      ┌─────────────────────────────────────────┐             │  │
│  │      │ STAGE 1: Video Processing (0-70%)      │             │  │
│  │      ├─────────────────────────────────────────┤             │  │
│  │      │ • Merge intro (if selected)             │             │  │
│  │      │ • Apply rotation                         │             │  │
│  │      │ • Scale resolution                       │             │  │
│  │      │ • Add text overlay                       │             │  │
│  │      │ • Adjust playback speed                  │             │  │
│  │      │                                          │             │  │
│  │      │ Output: temp_processed_*.mp4 ★NEW★      │             │  │
│  │      │    OR   finalOutput.mp4 (if no music)   │             │  │
│  │      └─────────────────────────────────────────┘             │  │
│  │                       │                                       │  │
│  │                       ▼                                       │  │
│  │      ┌─────────────────────────────────────────┐             │  │
│  │      │ STAGE 2: Audio Replacement (70-100%)    │             │  │
│  │      │              ★NEW STAGE★                │             │  │
│  │      ├─────────────────────────────────────────┤             │  │
│  │      │ if (musicFilePath != null) {            │             │  │
│  │      │                                          │             │  │
│  │      │   AudioReplacer.replaceAudio(           │             │  │
│  │      │     temp_processed_*.mp4,  // Input     │             │  │
│  │      │     background_music.*,    // Music     │             │  │
│  │      │     finalOutput.mp4        // Output    │             │  │
│  │      │   );                                     │             │  │
│  │      │                                          │             │  │
│  │      │   delete(temp_processed_*.mp4);         │             │  │
│  │      │ }                                        │             │  │
│  │      └─────────────────────────────────────────┘             │  │
│  │    }                                                          │  │
│  │  }                                                            │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                 │                                  │
│                                 ▼                                  │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │                    Output & Feedback                         │  │
│  ├─────────────────────────────────────────────────────────────┤  │
│  │ • ProgressBar (0-100%)                                       │  │
│  │ • tvProgress (Status messages)                               │  │
│  │ • Toast notifications                                        │  │
│  │ • Final video: Movies/SimpleVideoEditor/edited_*.mp4         │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────┐
│                        AudioReplacer.java                           │
│                      (Core Audio Engine)                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  public static boolean replaceAudio(                               │
│      String videoPath,       // temp_processed_*.mp4               │
│      String musicPath,       // background_music.*                 │
│      String outputPath,      // finalOutput.mp4                    │
│      ProgressCallback cb     // 0-100% progress                    │
│  )                                                                  │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │ Step 1: Extract Video Track (20-50%)                       │   │
│  │  • MediaExtractor reads video frames                        │   │
│  │  • MediaMuxer writes to output                              │   │
│  └────────────────────────────────────────────────────────────┘   │
│                             │                                      │
│                             ▼                                      │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │ Step 2: Extract & Loop Audio Track (50-100%)               │   │
│  │  • MediaExtractor reads audio samples                       │   │
│  │  • Loop if music < video duration                           │   │
│  │  • Truncate if music > video duration                       │   │
│  │  • MediaMuxer writes to output                              │   │
│  └────────────────────────────────────────────────────────────┘   │
│                             │                                      │
│                             ▼                                      │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │ Result: Video with replaced audio                           │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Data Flow Diagram

```
USER INTERACTION
       │
       ▼
┌─────────────┐
│ Select Video│────────────────────┐
└─────────────┘                    │
       │                           │
       ▼                           │
┌─────────────┐                    │
│ Fast Mode?  │                    │
└─────────────┘                    │
       │                           │
       ├───YES───► [Music Button Disabled]
       │                           │
       └───NO────► [Music Button Enabled]
                          │         │
                          ▼         │
                   ┌─────────────┐ │
                   │Select Music?│ │
                   └─────────────┘ │
                          │         │
                  ┌───────┴───────┐│
                  │               ││
              YES │           NO  ││
                  │               ││
                  ▼               ▼▼
          ┌──────────────┐   ┌──────────────┐
          │ Copy to Cache│   │ musicFilePath│
          │ background_  │   │    = null    │
          │  music.*     │   └──────────────┘
          └──────────────┘          │
                  │                 │
                  └────────┬────────┘
                           │
                           ▼
                   ┌──────────────┐
                   │Process Video │
                   └──────────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
      musicFilePath != null?     musicFilePath == null?
              │                         │
              ▼                         ▼
    ┌──────────────────┐      ┌──────────────────┐
    │ TWO-STAGE        │      │ SINGLE-STAGE     │
    │ PROCESSING       │      │ PROCESSING       │
    ├──────────────────┤      ├──────────────────┤
    │ 1. Process Video │      │ Process Video    │
    │    ↓ temp file   │      │    ↓ final out   │
    │ 2. Replace Audio │      └──────────────────┘
    │    ↓ final out   │                │
    │ 3. Delete temp   │                │
    └──────────────────┘                │
              │                         │
              └────────────┬────────────┘
                           │
                           ▼
                   ┌──────────────┐
                   │ Final Video  │
                   │   Saved!     │
                   └──────────────┘
```

## Progress Timeline

```
Without Music (Single Stage):
├──────────────────────────────────────────────────────────────────────┤
0%                     Video Processing                             100%


With Music Only (Two Stages):
├───────────────────────────────────────────┬──────────────────────────┤
0%              Video Processing         70%   Audio Replacement    100%


With Intro + Music (Three Stages):
├──────────────────────┬──────────────────────┬───────────────────────┤
0%    Intro Merge   50%  Video Processing  85%  Audio Replacement 100%
```

## File State Timeline

```
START
  │
  ├─ input_video.mp4          [Main video copied to cache]
  ├─ intro_video.mp4          [If selected]
  └─ background_music.mp3     [If selected]
  │
  ▼
PROCESSING
  │
  ├─ temp_merged.mp4          [Created if intro selected]
  └─ temp_processed_*.mp4     [Created if music selected]
  │
  ▼
COMPLETION
  │
  ├─ finalOutput.mp4          [Saved to Movies/SimpleVideoEditor/]
  │
  └─ Cleanup:
      • Delete temp_merged.mp4 (if created)
      • Delete temp_processed_*.mp4 (if created)
      • Keep cache files for future use (input, intro, music)
```

## UI State Management

```
┌─────────────────────────────────────────────────────────────┐
│                    Fast Mode Checkbox                       │
└─────────────────────────────────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
         CHECKED                      UNCHECKED
        (Fast Mode)                  (Full Mode)
              │                           │
              ▼                           ▼
    ┌─────────────────┐         ┌─────────────────┐
    │ DISABLED:       │         │ ENABLED:        │
    │ • Resolution    │         │ • Resolution    │
    │ • Speed         │         │ • Speed         │
    │ • Overlay       │         │ • Overlay       │
    │ • Intro         │         │ • Intro         │
    │ • Music ★NEW★   │         │ • Music ★NEW★   │
    └─────────────────┘         └─────────────────┘
```

## Error Handling Flow

```
                    ┌─────────────┐
                    │   Error?    │
                    └─────────────┘
                          │
          ┌───────────────┼───────────────┐
          │               │               │
          ▼               ▼               ▼
  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │Invalid Music │ │ Copy Failed  │ │Audio Replace │
  │    File      │ │              │ │   Failed     │
  └──────────────┘ └──────────────┘ └──────────────┘
          │               │               │
          ▼               ▼               ▼
  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ Toast Error  │ │ Toast Error  │ │ Copy temp to │
  │ Reset UI     │ │ Set path=null│ │ final output │
  │              │ │              │ │ Keep original│
  │              │ │              │ │ audio        │
  └──────────────┘ └──────────────┘ └──────────────┘
          │               │               │
          └───────────────┴───────────────┘
                          │
                          ▼
                  ┌──────────────┐
                  │User Notified │
                  │App Continues │
                  └──────────────┘
```

## Legend

```
★NEW★  - New component added for background music feature
┌─┐    - Component/module
├─┤    - Component with details
│      - Data flow / connection
▼      - Direction of flow
```

---

**Architecture Summary:**
- Clean separation between UI, business logic, and core processing
- Two-stage processing only when music is selected (efficiency)
- Comprehensive error handling with graceful degradation
- Automatic resource cleanup (temp files)
- Progress tracking at each stage
- User-friendly feedback throughout the process

**Key Design Decisions:**
1. **Optional Feature:** Music is completely optional, doesn't affect normal workflow
2. **Fast Mode Restriction:** Keeps Fast Mode promise of speed (no complex processing)
3. **Two-Stage Processing:** Ensures video quality before audio work
4. **Temp Files:** Allows fallback if audio replacement fails
5. **Background Threading:** UI remains responsive during file operations
