from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass
from pathlib import Path


class InvalidRppgMedia(ValueError):
    pass


@dataclass(frozen=True)
class VideoInspection:
    duration_seconds: float
    fps: float
    width: int
    height: int


class FfprobeMediaInspector:
    def __init__(self, executable: str = "ffprobe") -> None:
        self.executable = executable

    def inspect(self, path: Path) -> VideoInspection:
        try:
            result = subprocess.run(
                [self.executable, "-v", "error", "-select_streams", "v:0",
                 "-show_entries", "stream=avg_frame_rate,width,height:format=duration",
                 "-of", "json", str(path)],
                capture_output=True, text=True, timeout=30, check=True,
            )
            body = json.loads(result.stdout)
            stream = body["streams"][0]
            num, den = str(stream["avg_frame_rate"]).split("/", 1)
            fps = float(num) / float(den)
            inspection = VideoInspection(
                duration_seconds=float(body["format"]["duration"]), fps=fps,
                width=int(stream["width"]), height=int(stream["height"]),
            )
        except (subprocess.SubprocessError, OSError, ValueError, KeyError, IndexError, json.JSONDecodeError, ZeroDivisionError) as exc:
            raise InvalidRppgMedia("Video cannot be decoded") from exc
        if not 19.5 <= inspection.duration_seconds <= 20.5:
            raise InvalidRppgMedia("Video duration must be 19.5 to 20.5 seconds")
        if not 27.0 <= inspection.fps <= 33.0:
            raise InvalidRppgMedia("Video frame rate must be approximately 30fps")
        if inspection.width <= 0 or inspection.height <= 0:
            raise InvalidRppgMedia("Video dimensions are invalid")
        return inspection
