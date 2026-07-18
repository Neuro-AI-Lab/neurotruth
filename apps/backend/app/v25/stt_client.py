from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import httpx


class SttError(RuntimeError):
    code = "stt_unavailable"
    status_code = 503


class SttAudioTooLarge(SttError):
    code = "audio_too_large"
    status_code = 413


class SttUnsupportedAudio(SttError):
    code = "unsupported_audio"
    status_code = 415


class SttNoSpeech(SttError):
    code = "no_speech"
    status_code = 422


class SttUnavailable(SttError):
    code = "stt_unavailable"
    status_code = 503


class SttTimeout(SttError):
    code = "stt_timeout"
    status_code = 504


_ERRORS = {
    413: SttAudioTooLarge,
    415: SttUnsupportedAudio,
    422: SttNoSpeech,
    503: SttUnavailable,
    504: SttTimeout,
}


def _enabled(value: str | None) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


class SttClient:
    def __init__(
        self,
        base_url: str,
        *,
        enabled: bool,
        connect_timeout: float,
        read_timeout: float,
        max_upload_mib: int,
        tmpfs_root: Path,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.enabled = enabled
        self.read_timeout = read_timeout
        self.max_upload_bytes = max_upload_mib * 1024 * 1024
        self.tmpfs_root = Path(tmpfs_root)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(read_timeout, connect=connect_timeout),
            transport=transport,
        )

    @classmethod
    def from_env(cls) -> "SttClient":
        return cls(
            os.getenv("STT_BASE_URL", "http://stt:8001"),
            enabled=_enabled(os.getenv("STT_ENABLED", "false")),
            connect_timeout=float(os.getenv("STT_CONNECT_TIMEOUT_SECONDS", "5")),
            read_timeout=float(os.getenv("STT_READ_TIMEOUT_SECONDS", "120")),
            max_upload_mib=int(os.getenv("STT_MAX_UPLOAD_MIB", "10")),
            tmpfs_root=Path(os.getenv("STT_TMPFS_ROOT", "/dev/shm/neurotruth-stt")),
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def status(self) -> dict[str, Any]:
        if not self.enabled:
            return {
                "enabled": False,
                "available": False,
                "model": "whisper-large-v3-turbo",
                "requestedDevice": None,
                "actualDevice": None,
                "engine": None,
                "fallback": False,
                "fallbackReason": None,
                "errorCode": "stt_disabled",
            }
        try:
            response = await self._client.get(f"{self.base_url}/health")
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("invalid status response")
        except Exception:
            return {
                "enabled": True,
                "available": False,
                "model": "whisper-large-v3-turbo",
                "requestedDevice": None,
                "actualDevice": None,
                "engine": None,
                "fallback": False,
                "fallbackReason": None,
                "errorCode": "stt_unavailable",
            }
        return {
            "enabled": True,
            "available": bool(body.get("available")),
            "model": str(body.get("model") or "whisper-large-v3-turbo"),
            "requestedDevice": body.get("requestedDevice"),
            "actualDevice": body.get("actualDevice"),
            "engine": body.get("engine"),
            "fallback": bool(body.get("fallback", False)),
            "fallbackReason": body.get("fallbackReason"),
            "errorCode": body.get("errorCode"),
        }

    async def transcribe(self, path: Path, *, content_type: str) -> dict[str, Any]:
        if not self.enabled:
            raise SttUnavailable("stt_unavailable")
        try:
            with path.open("rb") as handle:
                response = await self._client.post(
                    f"{self.base_url}/transcribe",
                    data={"language": "ko"},
                    files={"audio": (path.name, handle, content_type)},
                )
        except httpx.TimeoutException as exc:
            raise SttTimeout("stt_timeout") from exc
        except (httpx.HTTPError, OSError) as exc:
            raise SttUnavailable("stt_unavailable") from exc
        if response.status_code != 200:
            error = _ERRORS.get(response.status_code, SttUnavailable)
            raise error(error.code)
        try:
            body = response.json()
            text = str(body.get("text") or "").strip()
            duration_ms = int(body.get("durationMs") or 0)
        except (TypeError, ValueError) as exc:
            raise SttUnavailable("stt_unavailable") from exc
        if not text:
            raise SttNoSpeech("no_speech")
        if duration_ms > 30_500:
            raise SttAudioTooLarge("audio_too_large")
        return {
            "text": text,
            "language": "ko",
            "durationMs": max(0, duration_ms),
            "model": str(body.get("model") or "whisper-large-v3-turbo"),
        }
