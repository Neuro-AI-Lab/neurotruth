from __future__ import annotations

import json
import secrets
from datetime import datetime, timezone
from typing import Any
from uuid import UUID

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.core.security.passwords import hash_password

from app.models.records import UserRecord
from app.repositories.postgres import V25Repository
from app.storage.sensor import EncryptedSensorStorage


class AdminOperationError(ValueError): pass
class AdminNotFound(AdminOperationError): pass
class AdminDeletionUnavailable(AdminOperationError): pass


_AAD_TABLE = {
    "patient_profile": "patient_profiles",
    "message": "messages",
    "assessment": "craving_assessments",
    "slot": "session_slots",
    "intervention": "interventions",
    "memory": "memory_snapshots",
    "report": "session_reports",
    "state_inference": "state_inferences",
}


class AdminService:
    def __init__(self, repository: V25Repository, keyring: AesGcmKeyring, storage: EncryptedSensorStorage) -> None:
        self.repository, self.keyring, self.storage = repository, keyring, storage
        self.rppg_repository = None
        self.rppg_storage = None
        self.dashboard_service = None

    def configure_rppg(self, repository: Any, storage: Any) -> None:
        self.rppg_repository, self.rppg_storage = repository, storage

    def configure_dashboard(self, service: Any) -> None:
        self.dashboard_service = service

    async def dashboard(self, patient_id: UUID, range_code: str) -> dict[str, Any]:
        if self.dashboard_service is None:
            raise AdminOperationError("Dashboard unavailable")
        return await self.dashboard_service.dashboard(patient_id, range_code, admin=True)

    async def patients(self) -> list[dict[str, Any]]:
        rows = await self.repository.list_patients()
        return [{key: (str(value) if isinstance(value, UUID) else value.isoformat() if hasattr(value, "isoformat") else value)
                 for key, value in row.items()} for row in rows]

    async def timeline(self, patient_id: UUID) -> list[dict[str, Any]]:
        return await self.repository.patient_timeline(patient_id)

    async def reveal(self, admin: UserRecord, resource_type: str, resource_id: UUID, reason: str) -> dict[str, Any]:
        if resource_type not in _AAD_TABLE: raise AdminNotFound("Resource not found")
        resource = await self.repository.sensitive_resource(resource_type, resource_id)
        if resource is None: raise AdminNotFound("Resource not found")
        values: dict[str, Any] = {}
        for column, packed in resource["fields"].items():
            plaintext = self.keyring.decrypt(
                packed,
                aad=aad_for(table=_AAD_TABLE[resource_type], column=column,
                            patient_id=str(resource["patient_id"]), record_id=str(resource["record_id"])),
            ).decode("utf-8")
            try: values[column.removesuffix("_encrypted")] = json.loads(plaintext)
            except json.JSONDecodeError: values[column.removesuffix("_encrypted")] = plaintext
        await self.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.sensitive_reveal",
                                    resource_type=resource_type, resource_id=resource_id,
                                    metadata={"reason": reason, "patientId": str(resource["patient_id"])})
        return {"resourceType": resource_type, "resourceId": str(resource_id),
                "patientId": str(resource["patient_id"]), "data": values}

    async def temporary_password(self, admin: UserRecord, patient_id: UUID, password: str, reason: str) -> None:
        if not await self.repository.set_temporary_password(patient_id, hash_password(password)):
            raise AdminNotFound("Patient not found")
        await self.repository.revoke_user_sessions(patient_id, reason="temporary_password", now=datetime.now(timezone.utc))
        await self.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.temporary_password",
                                    resource_type="user", resource_id=patient_id, metadata={"reason": reason})

    async def settings(self) -> dict[str, Any]:
        row = await self.repository.system_settings()
        if row is None: raise AdminOperationError("Settings unavailable")
        return {"interventionsEnabled": row.interventions_enabled, "chatTimeoutSeconds": row.chat_timeout_seconds}

    async def update_settings(self, admin: UserRecord, *, interventions_enabled: bool | None,
                              chat_timeout_seconds: int | None, admin_signup_code: str | None) -> dict[str, Any]:
        row = await self.repository.update_settings(
            interventions_enabled=interventions_enabled,
            chat_timeout_seconds=chat_timeout_seconds,
            admin_signup_code_hash=hash_password(admin_signup_code) if admin_signup_code else None,
        )
        await self.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.settings_update",
                                    resource_type="system_settings", metadata={
                                        "interventionsChanged": interventions_enabled is not None,
                                        "timeoutChanged": chat_timeout_seconds is not None,
                                        "signupCodeRotated": admin_signup_code is not None,
                                    })
        return {"interventionsEnabled": row.interventions_enabled, "chatTimeoutSeconds": row.chat_timeout_seconds}

    async def update_own_profile(self, user: UserRecord, changes: dict[str, Any]) -> None:
        values = dict(changes)
        if "name" in values:
            name = values.pop("name")
            values["name_encrypted"] = self.keyring.encrypt(
                str(name).encode("utf-8"),
                aad=aad_for(table="patient_profiles", column="name_encrypted",
                            patient_id=str(user.id), record_id=str(user.id)),
            ).pack() if name is not None else None
            values["encryption_key_version"] = self.keyring.current_key_id
        await self.repository.update_patient_profile(user.id, **values)
        await self.repository.audit(actor_id=user.id, actor_role="patient", action="patient.profile_update",
                                    resource_type="patient_profile", resource_id=user.id,
                                    metadata={"fields": sorted(changes)})

    async def own_profile_name(self, user: UserRecord) -> str | None:
        """Decrypt only the signed-in patient's own display name.

        This is not an administrator reveal: the caller is reading their own
        profile, so no reveal reason or sensitive-access audit is required.
        """
        if user.role != "patient":
            return None
        resource = await self.repository.sensitive_resource("patient_profile", user.id)
        if resource is None:
            return None
        packed = resource["fields"].get("name_encrypted")
        if packed is None:
            return None
        return self.keyring.decrypt(
            packed,
            aad=aad_for(
                table="patient_profiles",
                column="name_encrypted",
                patient_id=str(user.id),
                record_id=str(user.id),
            ),
        ).decode("utf-8")

    async def delete_patient(self, admin: UserRecord, patient_id: UUID, confirmation: str, reason: str) -> None:
        if confirmation != str(patient_id): raise AdminOperationError("Deletion confirmation does not match")
        if not await self.repository.mark_pending_deletion(patient_id): raise AdminNotFound("Patient not found")
        await self.repository.revoke_user_sessions(patient_id, reason="pending_deletion", now=datetime.now(timezone.utc))
        paths = await self.repository.patient_sensor_paths(patient_id)
        try:
            for path in paths: self.storage.delete(path)
            if self.rppg_repository is not None and self.rppg_storage is not None:
                captures = await self.rppg_repository.patient_capture_paths(patient_id)
                for capture in captures: self.rppg_storage.delete(capture["storage_uri"])
                await self.rppg_repository.delete_patient_rows(patient_id)
        except Exception as exc:
            if self.rppg_repository is not None:
                await self.rppg_repository.mark_patient_delete_failed(patient_id)
            await self.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.patient_delete_failed",
                                        resource_type="user", resource_id=patient_id, metadata={"reason": reason})
            raise AdminDeletionUnavailable("Patient deletion is pending retry") from exc
        await self.repository.tombstone_patient(patient_id, hash_password(secrets.token_urlsafe(48)))
        if self.rppg_repository is not None:
            await self.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.patient_rppg_deleted",
                                        resource_type="user", resource_id=patient_id,
                                        metadata={"reason": reason, "outcome": "success"})
        await self.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.patient_deleted",
                                    resource_type="user", resource_id=patient_id, metadata={"reason": reason})
