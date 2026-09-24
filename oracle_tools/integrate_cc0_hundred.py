"""Offline, allowlisted import of the two approved handoffs; never transforms audio."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / r"app\src\main\assets"
MUSIC = ASSETS / "music"
BASE = "5af80fd"
BUNDLE_SHA = "5758966656d0a148c2f4a8892b6825331424e736bd954622920c43b3066c5cd1"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main", type=Path, required=True)
    parser.add_argument("--preview", type=Path, required=True)
    args = parser.parse_args()
    bundle = args.main / r"delivery\cc0-109-native-library.zip"
    assert bundle.stat().st_size == 243213356 and digest(bundle) == BUNDLE_SHA
    baseline = json.loads(subprocess.check_output(
        ["git", "show", BASE + ":app/src/main/assets/music/catalog.json"], cwd=ROOT))
    original = baseline["tracks"]
    assert len(original) == 6
    for row in original:
        assert digest(ASSETS.joinpath(*row["path"].split("/"))) == row["sha256"]
    rows = list(original)
    with zipfile.ZipFile(bundle) as archive:
        assert archive.testzip() is None
        approved = json.loads(archive.read("catalog-assets.json"))
        assert len(approved["tracks"]) == 109
        evidence = [name for name in archive.namelist()
                    if name.startswith(("licenses/", "evidence/")) or name == "rights-review.json"]
        for name in evidence:
            assert ".." not in name.split("/") and not name.startswith("/")
            target = MUSIC / "abstraction-evidence" / Path(*name.split("/"))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
        for source in approved["tracks"]:
            assert source["license"] == "CC0-1.0" and source["loopVsComposition"] == "creator-loop"
            assert source["sampleRate"] == 44100 and source["channels"] in (1, 2)
            member = source["path"].replace("\\", "/")
            assert member.startswith("qualified/") and ".." not in member.split("/")
            data = archive.read(member)
            assert len(data) == source["bytes"]
            assert hashlib.sha256(data).hexdigest() == source["sha256"] == source["sourceSha256"]
            tags = source["moodStyle"].get("tags") or ""
            # Preserve creator tags, without inventing listening-derived moods or translations.
            tag = tags.split("|")[0].lower() or "unspecified"
            styles = {"ambient": ("Ambient", "氛围"), "electronic": ("Electronic", "电子"),
                      "jazz": ("Jazz", "爵士"), "chiptune": ("Chiptune", "芯片音乐"),
                      "spooky": ("Spooky", "诡异"), "silly": ("Silly", "诙谐"),
                      "funk": ("Funk", "放克"), "dnb": ("DnB", "鼓打贝斯"),
                      "dynamic": ("Dynamic", "动态"), "desert": ("Desert", "沙漠"),
                      "lofi": ("LoFi", "低保真"), "unspecified": ("Unspecified", "未指定")}
            style, style_zh = styles[tag]
            path = f"music/unspecified/{style.lower()}/{source['id']}.ogg"
            destination = ASSETS.joinpath(*path.split("/"))
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            frames = source["decodedFrames"]
            license_path = "music/abstraction-evidence/" + source["licensePath"].replace("\\", "/")
            assert digest(ASSETS.joinpath(*license_path.split("/"))) == source["licenseSha256"]
            rows.append(dict(
                id=source["id"], title=source["title"], titleZh=source["title"],
                mood="Unspecified", moodZh="未指定", style=style,
                styleZh=style_zh,
                path=path, author="Abstraction / Tallbeard Studios (Ben Burnes)",
                license=source["license"], licenseUrl=source["licenseURL"],
                source=source["creatorURL"], sha256=source["sha256"],
                sourceSha256=source["sourceSha256"], bytes=source["bytes"], frames=frames,
                format="ogg-vorbis-44100-" + ("mono" if source["channels"] == 1 else "stereo"),
                durationMs=frames * 1000 // 44100, sampleRate=44100, channels=source["channels"],
                originalFilename=source["archiveMember"], licenseFile=license_path,
                provenanceFile="music/abstraction-provenance.json", playbackKind="creator-loop",
                creatorTags=tags, creatorMetadata=source["moodStyle"],
                loop=dict(startFrameInclusive=0, endFrameExclusive=frames,
                          trimApplied=False, perceptuallyVerified=False)))
        write_json(MUSIC / "abstraction-provenance.json", dict(
            bundleSha256=BUNDLE_SHA, source="https://tallbeard.itch.io/music-loop-bundle",
            rightsReview="music/abstraction-evidence/rights-review.json",
            qualification="CC0 to the extent of the affirmer's rights; not a worldwide legal guarantee.",
            nonendorsement="Creator does not endorse AI/ML, NFTs or direct resale; do not imply endorsement.",
            sources=approved["tracks"]))
    preview = json.loads((args.preview / r"music\catalog.json").read_text(encoding="utf-8"))
    assert {r["id"] for r in preview["tracks"]} == {
        "shrine", "challenge-accepted", "blue-meadow-in-green-sky"}
    for row in preview["tracks"]:
        for name in (row["path"], row["licenseFile"]):
            source = args.preview.joinpath(*name.split("/"))
            destination = ASSETS.joinpath(*name.split("/"))
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        assert digest(ASSETS.joinpath(*row["path"].split("/"))) == row["sha256"]
        rows.append(row)
    for name in (r"music\preview-expansion-provenance.json", r"music\licenses\preview-expansion-CC0-1.0.txt"):
        shutil.copyfile(args.preview / name, ASSETS / name)
    assert len(rows) == len({r["id"] for r in rows}) == len({r["sha256"] for r in rows}) == 118
    assert sum(r["bytes"] for r in rows) == 256956282
    write_json(MUSIC / "catalog.json", dict(version=1, catalogId="offline-music-cc0-118", tracks=rows))
    write_json(MUSIC / "manifest.json", dict(version=1, files=[
        dict(path=p.relative_to(MUSIC).as_posix(), bytes=p.stat().st_size, sha256=digest(p))
        for p in sorted(MUSIC.rglob("*")) if p.is_file() and p.name != "manifest.json"]))
    print("118 actual originals; 112 added; 256956282 audio bytes; no transcode")


if __name__ == "__main__":
    main()
