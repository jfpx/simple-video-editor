"""Host-only processor regression using existing javac/java and cached Media3 1.5.1.

Run: python -B oracle_tools\\test_pitch_preserving_audio_processor.py
No Gradle, downloads, Android execution, oracle changes, or processor substitutions.
"""

import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    build = Path(r"app\build\pitch-processor-test")
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    aars = sorted((cache / r"caches\modules-2\files-2.1\androidx.media3\media3-common\1.5.1").rglob("*.aar"))
    guava = sorted((cache / r"caches\modules-2\files-2.1\com.google.guava\guava\33.3.1-android").rglob("*.jar"))
    android = Path(r".local-sdk\platforms\android-35\android.jar")
    if not aars or not guava or not android.is_file():
        raise RuntimeError("Existing Media3 1.5.1/Guava cache and local Android 35 SDK required")
    for tool in ("javac", "java"):
        if not shutil.which(tool):
            raise RuntimeError(f"Missing existing tool: {tool}")
    build.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aars[0]) as archive:
        (build / "media3-common.jar").write_bytes(archive.read("classes.jar"))
    classes = build / "classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, [classes, build / "media3-common.jar", android, guava[0]]))
    subprocess.run([
        "javac", "-encoding", "UTF-8", "-proc:none", "-implicit:none", "-cp", classpath,
        "-d", str(classes),
        r"app\src\main\java\com\simple\videoeditor\PitchPreservingAudioProcessor.java",
        r"app\src\main\java\com\simple\videoeditor\TimelineAudioProcessor.java",
        r"oracle_tools\PitchPreservingAudioProcessorTest.java",
        r"oracle_tools\TimelineAudioProcessorTest.java",
    ], check=True, timeout=120)
    subprocess.run([
        "java", "-Xmx512m", f"-Djava.io.tmpdir={build.resolve()}", "-cp", classpath,
        "com.simple.videoeditor.PitchPreservingAudioProcessorTest",
    ], check=True, timeout=600)
    subprocess.run([
        "java", "-Xmx512m", f"-Djava.io.tmpdir={build.resolve()}", "-cp", classpath,
        "com.simple.videoeditor.TimelineAudioProcessorTest",
    ], check=True, timeout=600)


if __name__ == "__main__":
    main()
