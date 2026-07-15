from __future__ import annotations

import base64
import json
import os
import struct
from dataclasses import dataclass
from typing import Mapping

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


MAGIC = b"NTG1"
NONCE_BYTES = 12
TAG_BYTES = 16


class EncryptionConfigurationError(ValueError):
    """Raised when the configured keyring cannot safely encrypt data."""


class DecryptionError(ValueError):
    """Sanitized authenticated-decryption failure."""


def aad_for(*, table: str, column: str, patient_id: str, record_id: str) -> bytes:
    """Bind ciphertext to its exact logical owner and storage location."""

    values = (table, column, patient_id, record_id)
    if any(not str(value).strip() for value in values):
        raise ValueError("AAD fields must be non-empty")
    return json.dumps(values, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


@dataclass(frozen=True)
class EncryptedEnvelope:
    key_id: str
    nonce: bytes
    ciphertext: bytes

    def __post_init__(self) -> None:
        if not self.key_id or len(self.key_id.encode("utf-8")) > 255:
            raise ValueError("Invalid encryption key id")
        if len(self.nonce) != NONCE_BYTES:
            raise ValueError("Invalid AES-GCM nonce length")
        if len(self.ciphertext) < TAG_BYTES:
            raise ValueError("Invalid AES-GCM ciphertext")

    def pack(self) -> bytes:
        key = self.key_id.encode("utf-8")
        return MAGIC + struct.pack("!B", len(key)) + key + self.nonce + self.ciphertext

    @classmethod
    def unpack(cls, value: bytes) -> "EncryptedEnvelope":
        try:
            if value[:4] != MAGIC or len(value) < 5:
                raise ValueError
            key_length = value[4]
            nonce_start = 5 + key_length
            key_id = value[5:nonce_start].decode("utf-8")
            nonce = value[nonce_start : nonce_start + NONCE_BYTES]
            ciphertext = value[nonce_start + NONCE_BYTES :]
            return cls(key_id=key_id, nonce=nonce, ciphertext=ciphertext)
        except (UnicodeDecodeError, ValueError, IndexError) as exc:
            raise DecryptionError("Encrypted data is invalid") from exc


class AesGcmKeyring:
    """Versioned AES-256-GCM keyring with a single current write key."""

    def __init__(self, keys: Mapping[str, bytes], current_key_id: str) -> None:
        clean = {str(key_id): bytes(key) for key_id, key in keys.items()}
        if not clean:
            raise EncryptionConfigurationError("Encryption keyring is empty")
        if any(not key_id or len(key) != 32 for key_id, key in clean.items()):
            raise EncryptionConfigurationError("Every encryption key must be 32 bytes")
        if current_key_id not in clean:
            raise EncryptionConfigurationError("Current encryption key is unavailable")
        self._keys = clean
        self.current_key_id = current_key_id

    @classmethod
    def from_config(cls, encoded: str, current_key_id: str) -> "AesGcmKeyring":
        """Parse either JSON (`{"v1":"..."}`) or `v1:base64,v2:base64`."""

        if not encoded or not current_key_id:
            raise EncryptionConfigurationError("Encryption key configuration is required")
        try:
            if encoded.lstrip().startswith("{"):
                raw = json.loads(encoded)
                if not isinstance(raw, dict):
                    raise ValueError
                pairs = raw.items()
            else:
                pairs = (item.split(":", 1) for item in encoded.split(",") if item.strip())
            keys = {
                str(key_id).strip(): base64.b64decode(str(value).strip(), validate=True)
                for key_id, value in pairs
            }
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            raise EncryptionConfigurationError("Encryption keyring format is invalid") from exc
        return cls(keys, current_key_id)

    def encrypt(self, plaintext: bytes, *, aad: bytes) -> EncryptedEnvelope:
        if not aad:
            raise ValueError("AAD is required")
        nonce = os.urandom(NONCE_BYTES)
        ciphertext = AESGCM(self._keys[self.current_key_id]).encrypt(nonce, plaintext, aad)
        return EncryptedEnvelope(self.current_key_id, nonce, ciphertext)

    def decrypt(self, envelope: EncryptedEnvelope | bytes, *, aad: bytes) -> bytes:
        if not aad:
            raise ValueError("AAD is required")
        parsed = EncryptedEnvelope.unpack(envelope) if isinstance(envelope, bytes) else envelope
        key = self._keys.get(parsed.key_id)
        if key is None:
            raise DecryptionError("Encrypted data cannot be decrypted")
        try:
            return AESGCM(key).decrypt(parsed.nonce, parsed.ciphertext, aad)
        except InvalidTag as exc:
            raise DecryptionError("Encrypted data cannot be decrypted") from exc
