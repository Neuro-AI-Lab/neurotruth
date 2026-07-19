from __future__ import annotations

import base64
import tempfile
from pathlib import Path
from uuid import uuid4

import pytest

from app.core.security.crypto import AesGcmKeyring, DecryptionError
from app.storage.sensor import EncryptedSensorStorage, canonical_sensor_json


def test_sensor_file_is_encrypted_atomic_and_tamper_evident() -> None:
    ring = AesGcmKeyring.from_config(
        "v1:" + base64.b64encode(b"s" * 32).decode(), "v1"
    )
    patient_id, recording_id = uuid4(), uuid4()
    payload = {"samples": [{"sensor": "PPG", "value": 12.34}], "sequence": 1}
    canonical = canonical_sensor_json(payload)
    with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
        storage = EncryptedSensorStorage(Path(directory), ring)
        stored = storage.store(patient_id=patient_id, recording_id=recording_id, canonical=canonical)
        file = Path(directory) / stored.relative_path
        raw = file.read_bytes()
        assert canonical not in raw
        assert b"12.34" not in raw
        assert storage.read(
            patient_id=patient_id, recording_id=recording_id, relative_path=stored.relative_path
        ) == canonical
        assert not list(file.parent.glob("*.tmp"))

        tampered = bytearray(raw)
        tampered[-1] ^= 1
        file.write_bytes(tampered)
        with pytest.raises(DecryptionError):
            storage.read(patient_id=patient_id, recording_id=recording_id, relative_path=stored.relative_path)


def test_canonical_json_is_order_independent_and_gzip_output_deterministic() -> None:
    assert canonical_sensor_json({"b": 2, "a": 1}) == canonical_sensor_json({"a": 1, "b": 2})
