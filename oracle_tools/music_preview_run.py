"""Durable, bounded validation state independent of a detached console."""
import json
import os
from pathlib import Path
import time
import traceback


def restore_owned_settings(stop, configure, original, token):
    # UI tests may leave a live/stalled Activity after their instrumentation exits.
    stop()
    configure("software-avc" if original["before"] else "default",
              preferences="restore", preference_token=token)
    checked = configure(preferences="verify", preference_token=token)
    if checked["after"] != original["before"] or checked["preferences"] != original["preferences"]:
        raise RuntimeError("Owned settings restoration mismatch")
    return checked


def atomic_json(path, value):
    path = Path(path)
    pending = path.with_name(path.name + ".pending")
    with pending.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    for attempt in range(20):
        try:
            os.replace(pending, path)
            return
        except PermissionError:
            if attempt == 19:
                raise
            time.sleep(.1)


class MusicPreviewRun:
    def __init__(self, evidence, progress, **metadata):
        self.evidence = Path(evidence)
        self.progress = Path(progress)
        self.summary = dict(metadata, status="RUNNING", pid=os.getpid(), native={}, errors=[])
        self.persist()

    def persist(self):
        self.summary["updatedAt"] = time.time()
        atomic_json(self.evidence / "summary.json", self.summary)

    def note(self, message):
        self.summary["milestone"] = message
        self.persist()
        with self.progress.open("a", encoding="utf-8") as stream:
            stream.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} pid={os.getpid()} "
                         f"{self.evidence.name}: {message}\n")
            stream.flush()
        try:
            print(message, flush=True)
        except (OSError, ValueError) as error:
            self.summary["consoleError"] = repr(error)
            self.persist()

    def execute(self, body, cleanup):
        status = "PASS"
        try:
            body()
        except BaseException as error:
            status = "CANCELLED" if isinstance(error, KeyboardInterrupt) else "FAIL"
            self.summary["errors"].append(dict(phase="body", error=repr(error),
                                               traceback=traceback.format_exc()))
        finally:
            # Persist the primary outcome before cleanup, even if cleanup itself fails.
            self.summary["status"] = status
            self.summary["cleanup"] = "RUNNING"
            try:
                self.persist()
            finally:
                try:
                    cleanup()
                    self.summary["cleanup"] = "PASS"
                except BaseException as error:
                    self.summary["cleanup"] = "FAIL"
                    self.summary["errors"].append(dict(phase="cleanup", error=repr(error),
                                                       traceback=traceback.format_exc()))
                    if status == "PASS":
                        status = "CANCELLED" if isinstance(error, KeyboardInterrupt) else "FAIL"
                finally:
                    self.summary["status"] = status
                    self.summary["finishedAt"] = time.time()
                    self.note("Terminal " + status + "; cleanup=" + self.summary["cleanup"])
        return 0 if status == "PASS" else 130 if status == "CANCELLED" else 1
