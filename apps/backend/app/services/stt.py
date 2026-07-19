from __future__ import annotations

from pathlib import Path
from typing import Any

from app.adapters.stt_client import SttClient


class SttService:
    """Application boundary around the separately deployed STT worker."""

    def __init__(self, client: SttClient) -> None:
        self.client = client

    @property
    def enabled(self) -> bool:
        return self.client.enabled

    @property
    def tmpfs_root(self) -> Path:
        return self.client.tmpfs_root

    @property
    def max_upload_bytes(self) -> int:
        return self.client.max_upload_bytes

    async def status(self) -> dict[str, Any]:
        return await self.client.status()

    async def transcribe(self, path: Path, *, content_type: str) -> dict[str, Any]:
        return await self.client.transcribe(path, content_type=content_type)

    async def close(self) -> None:
        await self.client.close()
