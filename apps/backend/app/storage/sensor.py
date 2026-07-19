from __future__ import annotations

import gzip
import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from app.core.security.crypto import AesGcmKeyring, EncryptedEnvelope, aad_for


@dataclass(frozen=True)
class StoredSensorFile:
    relative_path: str
    checksum_sha256: str
    byte_size: int
    key_id: str
    nonce: bytes


def canonical_sensor_json(payload: dict) -> bytes:
    return json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


class EncryptedSensorStorage:
    def __init__(self, root: Path, keyring: AesGcmKeyring) -> None:
        self.root = root.resolve()
        self.keyring = keyring

    def store(self, *, patient_id: UUID, recording_id: UUID, canonical: bytes) -> StoredSensorFile:
        compressed = gzip.compress(canonical, compresslevel=9, mtime=0)
        checksum = hashlib.sha256(canonical).hexdigest()
        aad = aad_for(
            table="sensor_recordings",
            column="raw_file",
            patient_id=str(patient_id),
            record_id=str(recording_id),
        )
        envelope = self.keyring.encrypt(compressed, aad=aad)
        patient_dir = self.root / str(patient_id)
        patient_dir.mkdir(parents=True, exist_ok=True)
        final = patient_dir / f"{recording_id}.ntg"
        temp = patient_dir / f".{recording_id}.{os.getpid()}.tmp"
        packed = envelope.pack()
        try:
            with temp.open("xb") as stream:
                stream.write(packed)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, final)
        finally:
            temp.unlink(missing_ok=True)
        return StoredSensorFile(
            relative_path=final.relative_to(self.root).as_posix(),
            checksum_sha256=checksum,
            byte_size=len(packed),
            key_id=envelope.key_id,
            nonce=envelope.nonce,
        )

    def read(self, *, patient_id: UUID, recording_id: UUID, relative_path: str) -> bytes:
        path = self._safe_path(relative_path)
        envelope = EncryptedEnvelope.unpack(path.read_bytes())
        aad = aad_for(
            table="sensor_recordings",
            column="raw_file",
            patient_id=str(patient_id),
            record_id=str(recording_id),
        )
        return gzip.decompress(self.keyring.decrypt(envelope, aad=aad))

    def delete(self, relative_path: str) -> None:
        self._safe_path(relative_path).unlink(missing_ok=True)

    def _safe_path(self, relative_path: str) -> Path:
        candidate = (self.root / relative_path).resolve()
        if candidate == self.root or self.root not in candidate.parents:
            raise ValueError("Invalid sensor storage path")
        return candidate
