from __future__ import annotations

import asyncio
import base64
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.core.security.tokens import create_access_token
from app.services.admin import AdminDeletionUnavailable, AdminService
from app.models.records import SystemSettingsRecord, UserRecord
from app.api.v1.routes.admin import router
from app.core.runtime import BackendRuntime


class Settings:
    jwt_signing_key = "j" * 32
    def assert_request_transport(self, url: str) -> None: pass


class UserRepo:
    def __init__(self, user: UserRecord, session_id: UUID) -> None: self.user, self.session_id = user, session_id
    async def user_by_id(self, user_id: UUID) -> UserRecord | None: return self.user if self.user.id == user_id else None
    async def auth_session_is_active(self, *, user_id, session_id, now): return user_id == self.user.id and session_id == self.session_id


class FakeAdminService:
    def __init__(self) -> None: self.calls: list[tuple[str, Any]] = []
    async def patients(self): return [{"id": str(uuid4()), "status": "active"}]
    async def timeline(self, patient_id): return [{"type": "prediction", "id": str(uuid4()), "status": "class_1"}]
    async def reveal(self, admin, resource_type, resource_id, reason):
        self.calls.append(("reveal", reason)); return {"resourceType": resource_type, "data": {"content": "secret"}}
    async def temporary_password(self, admin, patient_id, password, reason): self.calls.append(("temp", patient_id))
    async def delete_patient(self, admin, patient_id, confirmation, reason): self.calls.append(("delete", patient_id))
    async def settings(self): return {"interventionsEnabled": True, "chatTimeoutSeconds": 3600}
    async def update_settings(self, admin, **values): self.calls.append(("settings", values)); return {"interventionsEnabled": False, "chatTimeoutSeconds": 7200}


def client_for(role: str) -> tuple[TestClient, FakeAdminService, str]:
    user = UserRecord(uuid4(), f"{role}@example.com", "unused", role, "active", False, datetime.now(timezone.utc))
    service = FakeAdminService()
    session_id = uuid4()
    app = FastAPI(); app.include_router(router)
    app.state.backend_runtime = BackendRuntime(ready=True, settings=Settings(), repository=UserRepo(user, session_id), service=object(), admin_service=service, error_code=None)
    token, _ = create_access_token(subject=user.id, session_id=session_id, role=role, signing_key="j" * 32)
    return TestClient(app), service, token


def test_admin_routes_enforce_role_reason_and_contracts() -> None:
    patient, _, patient_token = client_for("patient")
    assert patient.get("/api/admin/patients", headers={"Authorization": f"Bearer {patient_token}"}).status_code == 403

    client, service, token = client_for("admin"); headers = {"Authorization": f"Bearer {token}"}
    patient_id, resource_id = uuid4(), uuid4()
    assert client.get("/api/admin/patients", headers=headers).status_code == 200
    assert client.get(f"/api/admin/patients/{patient_id}/timeline", headers=headers).json()[0]["type"] == "prediction"
    assert client.post(f"/api/admin/resources/message/{resource_id}/reveal", headers=headers, json={"reason": ""}).status_code == 422
    revealed = client.post(f"/api/admin/resources/message/{resource_id}/reveal", headers=headers, json={"reason": "clinical review"})
    assert revealed.json()["data"] == {"content": "secret"}
    assert revealed.headers["cache-control"] == "no-store"
    assert client.post(f"/api/admin/patients/{patient_id}/temporary-password", headers=headers,
                       json={"temporaryPassword": "temporary password value", "reason": "reset"}).status_code == 204
    assert client.request("DELETE", f"/api/admin/patients/{patient_id}", headers=headers,
                          json={"confirmation": str(patient_id), "reason": "requested"}).status_code == 204
    assert client.get("/api/admin/settings", headers=headers).json() == {"interventionsEnabled": True, "chatTimeoutSeconds": 3600}
    assert client.patch("/api/admin/settings", headers=headers,
                        json={"interventionsEnabled": False, "chatTimeoutSeconds": 7200}).json()["chatTimeoutSeconds"] == 7200
    assert client.patch("/api/admin/settings", headers=headers, json={"chatTimeoutSeconds": 59}).status_code == 422
    assert ("reveal", "clinical review") in service.calls


class BehaviorRepo:
    def __init__(self, packed: bytes, patient_id: UUID, record_id: UUID) -> None:
        self.packed, self.patient_id, self.record_id = packed, patient_id, record_id
        self.profile_name_encrypted: bytes | None = None
        self.audits: list[dict[str, Any]] = []; self.pending = False; self.tombstoned = False
        self.settings_row = SystemSettingsRecord(True, 3600, "hash")
        self.profile_update: dict[str, Any] = {}
        self.revoked_user_ids: list[UUID] = []
    async def sensitive_resource(self, resource_type, resource_id):
        if resource_type == "patient_profile":
            fields = (
                {"name_encrypted": self.profile_name_encrypted}
                if self.profile_name_encrypted is not None else {}
            )
            return {
                "patient_id": self.patient_id,
                "record_id": self.patient_id,
                "fields": fields,
            }
        return {"patient_id": self.patient_id, "record_id": self.record_id, "fields": {"content_encrypted": self.packed}}
    async def audit(self, **values): self.audits.append(values)
    async def set_temporary_password(self, patient_id, password_hash): return True
    async def revoke_user_sessions(self, user_id, *args, **kwargs): self.revoked_user_ids.append(user_id)
    async def system_settings(self): return self.settings_row
    async def update_settings(self, **values):
        self.settings_row = SystemSettingsRecord(values.get("interventions_enabled") if values.get("interventions_enabled") is not None else True,
                                                values.get("chat_timeout_seconds") or 3600,
                                                values.get("admin_signup_code_hash") or "hash")
        return self.settings_row
    async def update_patient_profile(self, patient_id, **values): self.profile_update = values
    async def mark_pending_deletion(self, patient_id): self.pending = True; return True
    async def patient_sensor_paths(self, patient_id): return ["patient/file.ntg"]
    async def tombstone_patient(self, patient_id, password_hash): self.tombstoned = True
    async def list_patients(self): return []
    async def patient_timeline(self, patient_id): return []


class FailingStorage:
    def delete(self, path: str) -> None: raise OSError("disk failure")


def test_reveal_aad_profile_encryption_and_delete_failure_are_audited() -> None:
    async def scenario() -> None:
        key = AesGcmKeyring.from_config("v1:" + base64.b64encode(b"a" * 32).decode(), "v1")
        patient_id, record_id = uuid4(), uuid4()
        packed = key.encrypt(b'{"answer":7}', aad=aad_for(table="messages", column="content_encrypted",
                             patient_id=str(patient_id), record_id=str(record_id))).pack()
        repo = BehaviorRepo(packed, patient_id, record_id)
        service = AdminService(repo, key, FailingStorage())
        admin = UserRecord(uuid4(), "admin@example.com", "", "admin", "active", False, datetime.now(timezone.utc))
        assert (await service.reveal(admin, "message", record_id, "review"))["data"]["content"] == {"answer": 7}
        patient = UserRecord(patient_id, "p@example.com", "", "patient", "active", False, datetime.now(timezone.utc))
        await service.update_own_profile(patient, {"name": "홍길동", "birth_year": 1990})
        assert b"\xed\x99\x8d\xea\xb8\xb8\xeb\x8f\x99" not in repo.profile_update["name_encrypted"]
        repo.profile_name_encrypted = repo.profile_update["name_encrypted"]
        assert await service.own_profile_name(patient) == "홍길동"
        await service.temporary_password(admin, patient_id, "temporary password value", "reset")
        assert patient_id in repo.revoked_user_ids
        try: await service.delete_patient(admin, patient_id, str(patient_id), "request")
        except AdminDeletionUnavailable: pass
        else: raise AssertionError("deletion should remain pending")
        assert repo.pending and not repo.tombstoned
        assert repo.audits[-1]["action"] == "admin.patient_delete_failed"

    asyncio.run(scenario())


def test_main_includes_admin_contract() -> None:
    from app import main
    paths = {getattr(route, "path", None) for route in main.app.routes}
    assert "/api/admin/patients" in paths
    assert "/api/admin/settings" in paths
