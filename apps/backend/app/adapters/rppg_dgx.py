from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx


@dataclass(frozen=True)
class DgxResponse:
    status_code: int
    payload: dict[str, Any] | None


class DgxClient:
    def __init__(self, base_url: str, *, connect_timeout: float, read_timeout: float,
                 transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = httpx.Timeout(connect=connect_timeout, read=read_timeout, write=read_timeout, pool=connect_timeout)
        self.transport = transport

    async def health(self) -> DgxResponse:
        try:
            async with httpx.AsyncClient(timeout=self.timeout, transport=self.transport) as client:
                response = await client.get(f"{self.base_url}/health")
                return DgxResponse(response.status_code, self._json(response))
        except (httpx.HTTPError, ValueError):
            return DgxResponse(0, None)

    async def infer(self, path: Path, job_id: str) -> DgxResponse:
        async with httpx.AsyncClient(timeout=self.timeout, transport=self.transport) as client:
            with path.open("rb") as stream:
                response = await client.post(
                    f"{self.base_url}/v1/rppg/infer",
                    files={"video": ("capture.mp4", stream, "video/mp4")},
                    data={"session_id": job_id, "include_waveform": "true"},
                )
        return DgxResponse(response.status_code, self._json(response))

    @staticmethod
    def _json(response: httpx.Response) -> dict[str, Any] | None:
        try:
            body = response.json()
        except ValueError:
            return None
        return body if isinstance(body, dict) else None
