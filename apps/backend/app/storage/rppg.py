from __future__ import annotations

import hashlib
import os
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator
from uuid import UUID

from app.core.security.crypto import AesGcmKeyring, EncryptedEnvelope, aad_for


@dataclass(frozen=True)
class StoredRppgVideo:
    relative_path: str
    checksum_sha256: str
    byte_size: int
    key_id: str
    nonce: bytes


class EncryptedRppgStorage:
    """Encrypted permanent video storage with short-lived restrictive plaintext."""

    def __init__(self, root: Path, tmpfs_root: Path, keyring: AesGcmKeyring) -> None:
        self.root = root.resolve()
        self.tmpfs_root = tmpfs_root.resolve()
        self.keyring = keyring

    def store_path(self, *, patient_id: UUID, capture_id: UUID, source: Path) -> StoredRppgVideo:
        raw = source.read_bytes()
        checksum = hashlib.sha256(raw).hexdigest()
        envelope = self.keyring.encrypt(raw, aad=self._aad(patient_id, capture_id))
        patient_dir = self.root / str(patient_id)
        patient_dir.mkdir(parents=True, exist_ok=True)
        final = patient_dir / f"{capture_id}.ntr"
        temp = patient_dir / f".{capture_id}.{os.getpid()}.tmp"
        packed = envelope.pack()
        try:
            with temp.open("xb") as stream:
                stream.write(packed)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, final)
        finally:
            temp.unlink(missing_ok=True)
        return StoredRppgVideo(
            relative_path=final.relative_to(self.root).as_posix(),
            checksum_sha256=checksum,
            byte_size=len(raw),
            key_id=envelope.key_id,
            nonce=envelope.nonce,
        )

    @contextmanager
    def plaintext(self, *, patient_id: UUID, capture_id: UUID, relative_path: str) -> Iterator[Path]:
        envelope = EncryptedEnvelope.unpack(self._safe_path(relative_path).read_bytes())
        raw = self.keyring.decrypt(envelope, aad=self._aad(patient_id, capture_id))
        self.tmpfs_root.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix="nt-rppg-", suffix=".mp4", dir=self.tmpfs_root)
        path = Path(name)
        try:
            os.chmod(path, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw)
            yield path
        finally:
            try:
                os.close(fd)
            except OSError:
                pass
            path.unlink(missing_ok=True)

    def read(self, *, patient_id: UUID, capture_id: UUID, relative_path: str) -> bytes:
        envelope = EncryptedEnvelope.unpack(self._safe_path(relative_path).read_bytes())
        return self.keyring.decrypt(envelope, aad=self._aad(patient_id, capture_id))

    def delete(self, relative_path: str) -> None:
        self._safe_path(relative_path).unlink(missing_ok=True)

    def _safe_path(self, relative_path: str) -> Path:
        candidate = (self.root / relative_path).resolve()
        if candidate == self.root or self.root not in candidate.parents:
            raise ValueError("Invalid rPPG storage path")
        return candidate

    @staticmethod
    def _aad(patient_id: UUID, capture_id: UUID) -> bytes:
        return aad_for(table="rppg_captures", column="video", patient_id=str(patient_id), record_id=str(capture_id))
