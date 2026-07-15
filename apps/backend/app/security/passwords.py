from __future__ import annotations

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
from argon2.low_level import Type


# RFC 9106 memory-constrained recommendation (64 MiB, 3 iterations, 4 lanes).
_HASHER = PasswordHasher(
    time_cost=3,
    memory_cost=65_536,
    parallelism=4,
    hash_len=32,
    salt_len=16,
    type=Type.ID,
)


def hash_password(password: str) -> str:
    if len(password) < 12 or len(password) > 1024:
        raise ValueError("Password must contain between 12 and 1024 characters")
    return _HASHER.hash(password)


def verify_password(password: str, encoded_hash: str) -> bool:
    try:
        return _HASHER.verify(encoded_hash, password)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def password_needs_rehash(encoded_hash: str) -> bool:
    try:
        return _HASHER.check_needs_rehash(encoded_hash)
    except InvalidHashError:
        return True
