from __future__ import annotations

from abc import ABC, abstractmethod
from contextlib import asynccontextmanager
from dataclasses import replace
from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from .models import (
    AuthSessionRecord,
    ConsentInput,
    ConsentRecord,
    RotationResult,
    SensorResultRecord,
    SystemSettingsRecord,
    UserRecord,
)


class RepositoryConflictError(ValueError):
    pass


class V25Repository(ABC):
    @abstractmethod
    async def create_patient(
        self,
        *,
        user_id: UUID,
        email: str,
        password_hash: str,
        name_encrypted: bytes | None,
        encryption_key_version: str,
        birth_year: int | None,
        gender: str | None,
        consent: ConsentInput,
    ) -> UserRecord: ...

    @abstractmethod
    async def create_admin(self, *, user_id: UUID, email: str, password_hash: str) -> UserRecord: ...

    @abstractmethod
    async def user_by_email(self, email: str) -> UserRecord | None: ...

    @abstractmethod
    async def user_by_id(self, user_id: UUID) -> UserRecord | None: ...

    @abstractmethod
    async def create_auth_session(self, session: AuthSessionRecord, device: dict[str, Any]) -> None: ...

    @abstractmethod
    async def auth_session_is_active(self, *, user_id: UUID, session_id: UUID, now: datetime) -> bool: ...

    @abstractmethod
    async def rotate_auth_session(
        self,
        *,
        presented_hash: str,
        new_session: AuthSessionRecord,
        device: dict[str, Any],
        now: datetime,
    ) -> RotationResult: ...

    @abstractmethod
    async def revoke_refresh(self, refresh_hash: str, *, reason: str, now: datetime) -> None: ...

    @abstractmethod
    async def revoke_user_sessions(self, user_id: UUID, *, reason: str, now: datetime) -> None: ...

    @abstractmethod
    async def update_password(self, user_id: UUID, password_hash: str) -> None: ...

    @abstractmethod
    async def append_consent(self, user_id: UUID, consent: ConsentInput) -> ConsentRecord: ...

    @abstractmethod
    async def current_consent(self, user_id: UUID) -> ConsentRecord | None: ...

    @abstractmethod
    async def ensure_system_settings(self, admin_signup_code_hash: str) -> SystemSettingsRecord: ...

    @abstractmethod
    async def system_settings(self) -> SystemSettingsRecord | None: ...

    @abstractmethod
    async def audit(
        self,
        *,
        actor_id: UUID | None,
        actor_role: str,
        action: str,
        resource_type: str | None = None,
        resource_id: UUID | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None: ...

    async def find_sensor_result(self, patient_id: UUID, client_window_id: UUID) -> SensorResultRecord | None:
        raise NotImplementedError

    async def ensure_craving_model_version(
        self, *, model_name: str, model_version: str, artifact_uri: str | None
    ) -> UUID:
        raise NotImplementedError

    async def persist_sensor_recording(self, **values: Any) -> SensorResultRecord:
        raise NotImplementedError

    async def persist_sensor_prediction(self, **values: Any) -> SensorResultRecord:
        raise NotImplementedError

    async def list_patients(self) -> list[dict[str, Any]]: raise NotImplementedError
    async def patient_timeline(self, patient_id: UUID) -> list[dict[str, Any]]: raise NotImplementedError
    async def sensitive_resource(self, resource_type: str, resource_id: UUID) -> dict[str, Any] | None: raise NotImplementedError
    async def set_temporary_password(self, patient_id: UUID, password_hash: str) -> bool: raise NotImplementedError
    async def update_settings(self, **values: Any) -> SystemSettingsRecord: raise NotImplementedError
    async def update_patient_profile(self, patient_id: UUID, **values: Any) -> None: raise NotImplementedError
    async def mark_pending_deletion(self, patient_id: UUID) -> bool: raise NotImplementedError
    async def patient_sensor_paths(self, patient_id: UUID) -> list[str]: raise NotImplementedError
    async def tombstone_patient(self, patient_id: UUID, password_hash: str) -> None: raise NotImplementedError
    async def open_session(self, **values: Any) -> dict[str, Any]: raise NotImplementedError
    async def owned_session(self, patient_id: UUID, session_id: UUID) -> dict[str, Any] | None: raise NotImplementedError
    async def session_messages(self, session_id: UUID) -> list[dict[str, Any]]: raise NotImplementedError
    async def append_message(self, **values: Any) -> UUID: raise NotImplementedError
    async def session_slots(self, session_id: UUID) -> list[dict[str, Any]]: raise NotImplementedError
    async def upsert_session_slot(self, **values: Any) -> None: raise NotImplementedError
    async def start_session(self, session_id: UUID) -> None: raise NotImplementedError
    async def finish_session(self, session_id: UUID, *, status: str, reason: str) -> None: raise NotImplementedError
    async def abandon_inactive_sessions(self, *, cutoff: datetime, now: datetime) -> list[dict[str, Any]]:
        raise NotImplementedError
    async def add_assessment(self, **values: Any) -> UUID: raise NotImplementedError
    async def add_intervention(self, **values: Any) -> UUID: raise NotImplementedError
    async def ensure_agent_model_version(self, **values: Any) -> UUID: raise NotImplementedError
    async def create_report_job(self, **values: Any) -> dict[str, Any]: raise NotImplementedError
    async def finish_report_job(self, **values: Any) -> None: raise NotImplementedError
    async def session_reports(self, patient_id: UUID, session_id: UUID) -> list[dict[str, Any]]: raise NotImplementedError
    async def add_memory_snapshot(self, **values: Any) -> UUID: raise NotImplementedError
    async def pending_report_jobs(self) -> list[dict[str, Any]]: return []
    async def update_dialogue_session(self, **values: Any) -> None: raise NotImplementedError
    async def session_interventions(self, session_id: UUID) -> list[dict[str, Any]]: raise NotImplementedError
    async def session_assessments(self, session_id: UUID) -> list[dict[str, Any]]: raise NotImplementedError
    async def inference_evidence(self, patient_id: UUID, session_id: UUID | None, *, since: datetime | None = None) -> dict[str, Any]: raise NotImplementedError
    async def add_state_inference(self, **values: Any) -> dict[str, Any]: raise NotImplementedError
    async def state_inferences(self, patient_id: UUID, session_id: UUID | None = None) -> list[dict[str, Any]]: raise NotImplementedError
    async def dashboard_rows(self, patient_id: UUID, since: datetime) -> dict[str, list[dict[str, Any]]]: raise NotImplementedError
    async def prediction_sensor(self, patient_id: UUID, prediction_id: UUID) -> dict[str, Any] | None: raise NotImplementedError
    async def ensure_rule_model_version(self, *, component: str, rule_version: str) -> UUID: raise NotImplementedError
    @asynccontextmanager
    async def message_turn(self, session_id: UUID):
        yield True


class SqlAlchemyV25Repository(V25Repository):
    """Small async repository over the fresh V2.5 schema."""

    def __init__(self, database_url: str, *, engine: AsyncEngine | None = None) -> None:
        self.engine = engine or create_async_engine(database_url, pool_pre_ping=True)

    @asynccontextmanager
    async def message_turn(self, session_id: UUID):
        key = f"message:{session_id}"
        async with self.engine.connect() as conn:
            acquired = bool(await conn.scalar(text("SELECT pg_try_advisory_lock(hashtext(:key))"), {"key": key}))
            try:
                yield acquired
            finally:
                if acquired:
                    await conn.execute(text("SELECT pg_advisory_unlock(hashtext(:key))"), {"key": key})

    async def close(self) -> None:
        await self.engine.dispose()

    @staticmethod
    def _user(row: Any) -> UserRecord:
        return UserRecord(
            id=row["id"], email=row["email"], password_hash=row["password_hash"],
            role=row["role"], status=row["status"],
            must_change_password=row["must_change_password"], created_at=row["created_at"],
        )

    @staticmethod
    def _consent(row: Any) -> ConsentRecord:
        return ConsentRecord(
            id=row["id"], user_id=row["user_id"], tos=row["tos"],
            privacy=row["privacy"], sensitive=row["sensitive"], biosignal=row["biosignal"],
            ai_analysis=row["ai_analysis"], notification=row["notification"],
            report_generation=row["report_generation"], tos_version=row["tos_version"],
            privacy_version=row["privacy_version"],
            consent_form_version=row["consent_form_version"], collected_at=row["collected_at"],
            camera_rppg=row.get("camera_rppg", False),
            face_video_retention=row.get("face_video_retention", False),
        )

    async def create_patient(self, **values: Any) -> UserRecord:
        consent: ConsentInput = values.pop("consent")
        try:
            async with self.engine.begin() as conn:
                row = (await conn.execute(text("""
                    INSERT INTO users (id,email,password_hash,role,status)
                    VALUES (:user_id,:email,:password_hash,'patient','active')
                    RETURNING id,email,password_hash,role,status,must_change_password,created_at
                """), values)).mappings().one()
                await conn.execute(text("""
                    INSERT INTO patient_profiles
                      (user_id,name_encrypted,birth_year,gender,pseudonymous_id,encryption_key_version)
                    VALUES (:user_id,:name_encrypted,:birth_year,:gender,:pseudonymous_id,:encryption_key_version)
                """), {**values, "pseudonymous_id": f"patient-{str(values['user_id'])[:12]}"})
                await self._insert_consent(conn, values["user_id"], consent)
            return self._user(row)
        except IntegrityError as exc:
            raise RepositoryConflictError("Account already exists") from exc

    async def create_admin(self, *, user_id: UUID, email: str, password_hash: str) -> UserRecord:
        try:
            async with self.engine.begin() as conn:
                row = (await conn.execute(text("""
                    INSERT INTO users (id,email,password_hash,role,status)
                    VALUES (:id,:email,:password_hash,'admin','active')
                    RETURNING id,email,password_hash,role,status,must_change_password,created_at
                """), {"id": user_id, "email": email, "password_hash": password_hash})).mappings().one()
            return self._user(row)
        except IntegrityError as exc:
            raise RepositoryConflictError("Account already exists") from exc

    async def user_by_email(self, email: str) -> UserRecord | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT id,email,password_hash,role,status,must_change_password,created_at
                FROM users WHERE lower(email)=lower(:email) AND deleted_at IS NULL
            """), {"email": email})).mappings().one_or_none()
        return self._user(row) if row else None

    async def user_by_id(self, user_id: UUID) -> UserRecord | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT id,email,password_hash,role,status,must_change_password,created_at
                FROM users WHERE id=:id AND deleted_at IS NULL
            """), {"id": user_id})).mappings().one_or_none()
        return self._user(row) if row else None

    async def create_auth_session(self, session: AuthSessionRecord, device: dict[str, Any]) -> None:
        async with self.engine.begin() as conn:
            await self._insert_auth(conn, session, device)

    async def auth_session_is_active(self, *, user_id: UUID, session_id: UUID, now: datetime) -> bool:
        async with self.engine.connect() as conn:
            active = await conn.scalar(text("""
                SELECT EXISTS (
                    SELECT 1 FROM auth_sessions
                    WHERE id=:session_id AND user_id=:user_id
                      AND revoked_at IS NULL AND replaced_by_session_id IS NULL
                      AND expires_at>:now
                )
            """), {"session_id": session_id, "user_id": user_id, "now": now})
        return bool(active)

    async def rotate_auth_session(self, *, presented_hash: str, new_session: AuthSessionRecord, device: dict[str, Any], now: datetime) -> RotationResult:
        async with self.engine.begin() as conn:
            row = (await conn.execute(text("""
                SELECT a.*,u.email,u.password_hash,u.role,u.status,u.must_change_password,u.created_at AS user_created_at
                FROM auth_sessions a JOIN users u ON u.id=a.user_id
                WHERE a.refresh_token_hash=:token_hash FOR UPDATE
            """), {"token_hash": presented_hash})).mappings().one_or_none()
            if row is None:
                return RotationResult("invalid")
            if row["revoked_at"] is not None or row["replaced_by_session_id"] is not None:
                await conn.execute(text("""
                    UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,:now),
                      revocation_reason=COALESCE(revocation_reason,'replay')
                    WHERE token_family_id=:family
                """), {"now": now, "family": row["token_family_id"]})
                return RotationResult("replayed")
            if row["expires_at"] <= now or row["status"] != "active":
                return RotationResult("invalid")
            effective_session = replace(
                new_session,
                user_id=row["user_id"],
                token_family_id=row["token_family_id"],
                parent_session_id=row["id"],
            )
            await self._insert_auth(conn, effective_session, device)
            await conn.execute(text("""
                UPDATE auth_sessions SET revoked_at=:now,revocation_reason='rotated',
                  replaced_by_session_id=:replacement,last_used_at=:now WHERE id=:id
            """), {"now": now, "replacement": new_session.id, "id": row["id"]})
            user = UserRecord(
                id=row["user_id"], email=row["email"], password_hash=row["password_hash"],
                role=row["role"], status=row["status"], must_change_password=row["must_change_password"],
                created_at=row["user_created_at"],
            )
            return RotationResult("rotated", user)

    async def revoke_refresh(self, refresh_hash: str, *, reason: str, now: datetime) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,:now),
                  revocation_reason=COALESCE(revocation_reason,:reason)
                WHERE refresh_token_hash=:token_hash
            """), {"now": now, "reason": reason, "token_hash": refresh_hash})

    async def revoke_user_sessions(self, user_id: UUID, *, reason: str, now: datetime) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,:now),
                  revocation_reason=COALESCE(revocation_reason,:reason) WHERE user_id=:user_id
            """), {"now": now, "reason": reason, "user_id": user_id})

    async def update_password(self, user_id: UUID, password_hash: str) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE users SET password_hash=:password_hash,must_change_password=false WHERE id=:id
            """), {"id": user_id, "password_hash": password_hash})

    async def append_consent(self, user_id: UUID, consent: ConsentInput) -> ConsentRecord:
        async with self.engine.begin() as conn:
            row = await self._insert_consent(conn, user_id, consent)
        return self._consent(row)

    async def current_consent(self, user_id: UUID) -> ConsentRecord | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT * FROM consent_snapshots WHERE user_id=:user_id
                ORDER BY collected_at DESC,id DESC LIMIT 1
            """), {"user_id": user_id})).mappings().one_or_none()
        return self._consent(row) if row else None

    async def ensure_system_settings(self, admin_signup_code_hash: str) -> SystemSettingsRecord:
        async with self.engine.begin() as conn:
            row = (await conn.execute(text("""
                INSERT INTO system_settings (id,admin_signup_code_hash) VALUES (true,:code)
                ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id
                RETURNING interventions_enabled,chat_timeout_seconds,admin_signup_code_hash
            """), {"code": admin_signup_code_hash})).mappings().one()
        return SystemSettingsRecord(**row)

    async def system_settings(self) -> SystemSettingsRecord | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT interventions_enabled,chat_timeout_seconds,admin_signup_code_hash
                FROM system_settings WHERE id=true
            """))).mappings().one_or_none()
        return SystemSettingsRecord(**row) if row else None

    async def audit(self, *, actor_id: UUID | None, actor_role: str, action: str, resource_type: str | None = None, resource_id: UUID | None = None, metadata: dict[str, Any] | None = None) -> None:
        import json
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                INSERT INTO audit_logs (actor_id,actor_role,action,resource_type,resource_id,metadata)
                VALUES (:actor_id,:actor_role,:action,:resource_type,:resource_id,CAST(:metadata AS jsonb))
            """), {"actor_id": actor_id, "actor_role": actor_role, "action": action,
                    "resource_type": resource_type, "resource_id": resource_id,
                    "metadata": json.dumps(metadata or {}, separators=(",", ":"))})

    async def find_sensor_result(self, patient_id: UUID, client_window_id: UUID) -> SensorResultRecord | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT r.id AS recording_id,r.client_window_id,r.checksum_sha256,
                       p.id AS prediction_id,p.output_metadata,
                       (SELECT a.id FROM craving_alerts a
                        WHERE a.trigger_prediction_id=p.id ORDER BY a.triggered_at DESC LIMIT 1) AS alert_id
                FROM sensor_recordings r
                LEFT JOIN LATERAL (
                    SELECT prediction.* FROM craving_predictions prediction
                    WHERE prediction.sensor_recording_id=r.id AND prediction.patient_id=r.patient_id
                    ORDER BY prediction.predicted_at DESC LIMIT 1
                ) p ON true
                WHERE r.patient_id=:patient_id AND r.client_window_id=:client_window_id
                LIMIT 1
            """), {"patient_id": patient_id, "client_window_id": client_window_id})).mappings().one_or_none()
        if row is None:
            return None
        return SensorResultRecord(
            recording_id=row["recording_id"], prediction_id=row["prediction_id"],
            alert_id=row["alert_id"], client_window_id=row["client_window_id"],
            checksum_sha256=row["checksum_sha256"], prediction=dict(row["output_metadata"] or {}),
        )

    async def ensure_craving_model_version(self, *, model_name: str, model_version: str, artifact_uri: str | None) -> UUID:
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext('neurotruth-craving-model'))"))
            active = (await conn.execute(text("""
                SELECT id,model_name,model_version FROM model_versions
                WHERE component='craving_model' AND is_active LIMIT 1
            """))).mappings().one_or_none()
            if active is not None and active["model_name"] == model_name and active["model_version"] == model_version:
                return active["id"]
            await conn.execute(text("""
                UPDATE model_versions SET is_active=false
                WHERE component='craving_model' AND is_active
            """))
            return (await conn.execute(text("""
                INSERT INTO model_versions
                  (component,model_name,model_version,inference_task,output_schema,config,artifact_uri,is_active)
                VALUES ('craving_model',:name,:version,'multiclass_classification',
                  '{"classes":[{"index":0,"code":"low"},{"index":1,"code":"mid"},{"index":2,"code":"high"}]}'::jsonb,
                  '{"window_sec":10}'::jsonb,:artifact,true)
                ON CONFLICT (component,model_name,model_version) DO UPDATE SET
                  artifact_uri=EXCLUDED.artifact_uri,is_active=true
                RETURNING id
            """), {"name": model_name, "version": model_version, "artifact": artifact_uri})).scalar_one()

    async def persist_sensor_recording(self, **values: Any) -> SensorResultRecord:
        import json
        try:
            async with self.engine.begin() as conn:
                await conn.execute(text("""
                    INSERT INTO sensor_recordings
                      (id,patient_id,client_window_id,storage_uri,file_format,modalities,
                       device_info,sample_rates,started_at,ended_at,bytes,checksum_sha256,
                       encryption_key_version,encryption_nonce,consent_snapshot_id)
                    VALUES (:recording_id,:patient_id,:client_window_id,:storage_uri,'binary',:modalities,
                       CAST(:device_info AS jsonb),CAST(:sample_rates AS jsonb),:started_at,:ended_at,
                       :bytes,:checksum,:key_version,:nonce,:consent_snapshot_id)
                """), {
                    **values,
                    "device_info": json.dumps(values.get("device_info") or {}, separators=(",", ":")),
                    "sample_rates": json.dumps(values.get("sample_rates") or {}, separators=(",", ":")),
                })
            return SensorResultRecord(
                recording_id=values["recording_id"], prediction_id=None, alert_id=None,
                client_window_id=values["client_window_id"], checksum_sha256=values["checksum"],
                prediction={},
            )
        except IntegrityError as exc:
            raise RepositoryConflictError("Sensor window already exists") from exc

    async def persist_sensor_prediction(self, **values: Any) -> SensorResultRecord:
        import json
        try:
            async with self.engine.begin() as conn:
                recording = (await conn.execute(text("""
                    SELECT id,patient_id,client_window_id,checksum_sha256
                    FROM sensor_recordings
                    WHERE id=:recording_id AND patient_id=:patient_id
                    FOR UPDATE
                """), values)).mappings().one_or_none()
                if recording is None:
                    raise RepositoryConflictError("Sensor recording is unavailable")
                existing = (await conn.execute(text("""
                    SELECT p.id AS prediction_id,p.output_metadata,
                           (SELECT a.id FROM craving_alerts a
                            WHERE a.trigger_prediction_id=p.id ORDER BY a.triggered_at DESC LIMIT 1) AS alert_id
                    FROM craving_predictions p
                    WHERE p.sensor_recording_id=:recording_id AND p.patient_id=:patient_id
                    ORDER BY p.predicted_at DESC LIMIT 1
                """), values)).mappings().one_or_none()
                if existing is not None:
                    return SensorResultRecord(
                        recording_id=recording["id"], prediction_id=existing["prediction_id"],
                        alert_id=existing["alert_id"], client_window_id=recording["client_window_id"],
                        checksum_sha256=recording["checksum_sha256"],
                        prediction=dict(existing["output_metadata"] or {}),
                    )
                await conn.execute(text("""
                    INSERT INTO craving_predictions
                      (id,patient_id,sensor_recording_id,window_started_at,window_ended_at,
                       input_modalities,model_version_id,predicted_class_index,predicted_class_code,
                       predicted_class_probability,class_probabilities,signal_quality,motion_context,
                       quality_gate_passed,output_metadata,predicted_at)
                    VALUES (:prediction_id,:patient_id,:recording_id,:started_at,:ended_at,
                       :modalities,:model_version_id,:class_index,:class_code,:confidence,
                       CAST(:probabilities AS jsonb),'{}'::jsonb,'{}'::jsonb,true,
                       CAST(:prediction_json AS jsonb),:predicted_at)
                """), {
                    **values,
                    "class_code": f"class_{values['class_index']}",
                    "probabilities": json.dumps(values.get("class_probabilities") or {}, separators=(",", ":")),
                    "prediction_json": json.dumps(values["prediction"], separators=(",", ":")),
                })
                if values.get("alert_id") is not None:
                    alert = values.get("alert") or {}
                    await conn.execute(text("""
                        INSERT INTO craving_alerts
                          (id,patient_id,trigger_prediction_id,rule_code,rule_version,trigger_reason,
                           status,notification_channel,triggered_at)
                        VALUES (:alert_id,:patient_id,:prediction_id,:rule_code,'v1',
                          CAST(:reason AS jsonb),'triggered','in_app',:predicted_at)
                    """), {
                        **values,
                        "rule_code": str(alert.get("alertAction") or alert.get("triggerReason") or "threshold")[:64],
                        "reason": json.dumps(alert, separators=(",", ":")),
                    })
            return SensorResultRecord(
                recording_id=recording["id"], prediction_id=values["prediction_id"],
                alert_id=values.get("alert_id"), client_window_id=recording["client_window_id"],
                checksum_sha256=recording["checksum_sha256"], prediction=dict(values["prediction"]),
            )
        except IntegrityError as exc:
            raise RepositoryConflictError("Sensor prediction already exists") from exc

    async def list_patients(self) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT u.id,u.email,u.status,u.must_change_password,u.created_at,
                       p.birth_year,p.gender,
                       (SELECT collected_at FROM consent_snapshots c WHERE c.user_id=u.id
                        ORDER BY collected_at DESC LIMIT 1) AS consent_updated_at
                FROM users u JOIN patient_profiles p ON p.user_id=u.id
                WHERE u.role='patient' ORDER BY u.created_at DESC
            """))).mappings().all()
        return [dict(row) for row in rows]

    async def patient_timeline(self, patient_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT kind,id,event_at,status FROM (
                  SELECT 'sensor'::text kind,id,created_at event_at,'recorded'::text status
                    FROM sensor_recordings WHERE patient_id=:patient
                  UNION ALL SELECT 'prediction',id,predicted_at,predicted_class_code
                    FROM craving_predictions WHERE patient_id=:patient
                  UNION ALL SELECT 'alert',id,triggered_at,status
                    FROM craving_alerts WHERE patient_id=:patient
                  UNION ALL SELECT 'session',id,created_at,status
                    FROM sessions WHERE patient_id=:patient
                  UNION ALL SELECT 'message',m.id,m.created_at,m.role
                    FROM messages m JOIN sessions s ON s.id=m.session_id
                    WHERE s.patient_id=:patient
                ) events ORDER BY event_at DESC LIMIT 500
            """), {"patient": patient_id})).mappings().all()
        return [{"type": row["kind"], "id": str(row["id"]),
                 "timestamp": row["event_at"].isoformat(), "status": row["status"],
                 **({"resourceType": "message", "resourceId": str(row["id"]), "role": row["status"]}
                    if row["kind"] == "message" else {})} for row in rows]

    async def sensitive_resource(self, resource_type: str, resource_id: UUID) -> dict[str, Any] | None:
        queries = {
            "patient_profile": ("patient_profiles", "user_id", "user_id", ["name_encrypted"]),
            "message": ("messages m JOIN sessions s ON s.id=m.session_id", "m.id", "s.patient_id", ["content_encrypted"]),
            "assessment": ("craving_assessments a JOIN sessions s ON s.id=a.session_id", "a.id", "s.patient_id", ["answers_encrypted"]),
            "slot": ("session_slots x JOIN sessions s ON s.id=x.session_id", "x.id", "s.patient_id", ["value_encrypted"]),
            "intervention": ("interventions i JOIN sessions s ON s.id=i.session_id", "i.id", "s.patient_id", ["selection_basis_encrypted", "content_encrypted", "user_feedback_encrypted"]),
            "memory": ("memory_snapshots", "id", "patient_id", ["summary_encrypted"]),
            "report": ("session_reports r JOIN sessions s ON s.id=r.session_id", "r.id", "s.patient_id", ["content_encrypted"]),
            "state_inference": ("state_inferences", "id", "patient_id", ["payload_encrypted", "summary_encrypted"]),
        }
        definition = queries.get(resource_type)
        if definition is None:
            return None
        source, id_column, patient_column, columns = definition
        select_columns = ",".join(columns)
        async with self.engine.connect() as conn:
            row = (await conn.execute(text(
                f"SELECT {id_column} AS record_id,{patient_column} AS patient_id,{select_columns} "
                f"FROM {source} WHERE {id_column}=:id LIMIT 1"
            ), {"id": resource_id})).mappings().one_or_none()
        if row is None:
            return None
        return {"record_id": row["record_id"], "patient_id": row["patient_id"],
                "fields": {column: row[column] for column in columns if row[column] is not None}}

    async def set_temporary_password(self, patient_id: UUID, password_hash: str) -> bool:
        async with self.engine.begin() as conn:
            result = await conn.execute(text("""
                UPDATE users SET password_hash=:password,must_change_password=true
                WHERE id=:id AND role='patient' AND status='active'
            """), {"password": password_hash, "id": patient_id})
        return result.rowcount == 1

    async def update_settings(self, **values: Any) -> SystemSettingsRecord:
        assignments, params = [], {}
        for column in ("interventions_enabled", "chat_timeout_seconds", "admin_signup_code_hash"):
            if column in values and values[column] is not None:
                assignments.append(f"{column}=:{column}")
                params[column] = values[column]
        if not assignments:
            current = await self.system_settings()
            if current is None: raise RuntimeError("System settings unavailable")
            return current
        async with self.engine.begin() as conn:
            row = (await conn.execute(text(
                "UPDATE system_settings SET " + ",".join(assignments) +
                " WHERE id=true RETURNING interventions_enabled,chat_timeout_seconds,admin_signup_code_hash"
            ), params)).mappings().one()
        return SystemSettingsRecord(**row)

    async def update_patient_profile(self, patient_id: UUID, **values: Any) -> None:
        assignments, params = [], {"id": patient_id}
        for column in ("name_encrypted", "encryption_key_version", "birth_year", "gender"):
            if column in values:
                assignments.append(f"{column}=:{column}")
                params[column] = values[column]
        if assignments:
            async with self.engine.begin() as conn:
                await conn.execute(text("UPDATE patient_profiles SET " + ",".join(assignments) + " WHERE user_id=:id"), params)

    async def mark_pending_deletion(self, patient_id: UUID) -> bool:
        async with self.engine.begin() as conn:
            result = await conn.execute(text("""
                UPDATE users SET status='pending_deletion' WHERE id=:id AND role='patient'
                  AND status IN ('active','pending_deletion')
            """), {"id": patient_id})
        return result.rowcount == 1

    async def patient_sensor_paths(self, patient_id: UUID) -> list[str]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT storage_uri FROM sensor_recordings WHERE patient_id=:id AND deleted_at IS NULL
            """), {"id": patient_id})).scalars().all()
        return list(rows)

    async def tombstone_patient(self, patient_id: UUID, password_hash: str) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("DELETE FROM auth_sessions WHERE user_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM state_inferences WHERE patient_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM memory_snapshots WHERE patient_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM sessions WHERE patient_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM craving_predictions WHERE patient_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM sensor_recordings WHERE patient_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM consent_snapshots WHERE user_id=:id"), {"id": patient_id})
            await conn.execute(text("DELETE FROM patient_profiles WHERE user_id=:id"), {"id": patient_id})
            await conn.execute(text("""
                UPDATE users SET email='deleted+' || id::text || '@invalid.local',password_hash=:password,
                  status='pending_deletion',must_change_password=false,deleted_at=now() WHERE id=:id
            """), {"id": patient_id, "password": password_hash})

    async def open_session(self, **values: Any) -> dict[str, Any]:
        timed_out_session_id = None
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext(:key))"), {"key": f"session:{values['patient_id']}"})
            active = (await conn.execute(text("""
                SELECT * FROM sessions WHERE patient_id=:patient AND status IN ('created','in_progress')
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
            """), {"patient": values["patient_id"]})).mappings().one_or_none()
            if active is not None:
                age = (values["now"] - active["updated_at"]).total_seconds()
                if age <= values["timeout_seconds"]:
                    return dict(active)
                await conn.execute(text("""
                    UPDATE sessions SET status='abandoned',completion_reason='timeout',ended_at=:now,
                      interaction_phase=CASE WHEN interaction_phase IS NULL THEN NULL ELSE 'abandoned' END
                    WHERE id=:id
                """), {"now": values["now"], "id": active["id"]})
                timed_out_session_id = active["id"]
            row = (await conn.execute(text("""
                INSERT INTO sessions
                  (id,patient_id,trigger_alert_id,session_type,status,started_at,
                   interaction_phase,dialogue_state_encrypted,dialogue_state_key_version)
                VALUES (:id,:patient,:alert,:type,'in_progress',:now,
                        'safety_check',:dialogue_state,:dialogue_key) RETURNING *
            """), {"id": values["session_id"], "patient": values["patient_id"],
                    "alert": values.get("trigger_alert_id"), "type": values["session_type"],
                    "now": values["now"], "dialogue_state": values["dialogue_state_encrypted"],
                    "dialogue_key": values["dialogue_state_key_version"]})).mappings().one()
        result = dict(row)
        result["_timed_out_session_id"] = timed_out_session_id
        return result

    async def owned_session(self, patient_id: UUID, session_id: UUID) -> dict[str, Any] | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("SELECT * FROM sessions WHERE id=:id AND patient_id=:patient"),
                                      {"id": session_id, "patient": patient_id})).mappings().one_or_none()
        return dict(row) if row else None

    async def session_messages(self, session_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT id,sequence_no,role,content_encrypted,created_at FROM messages
                WHERE session_id=:id ORDER BY sequence_no
            """), {"id": session_id})).mappings().all()
        return [dict(row) for row in rows]

    async def append_message(self, **values: Any) -> UUID:
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT 1 FROM sessions WHERE id=:session FOR UPDATE"), {"session": values["session_id"]})
            sequence = await conn.scalar(text("""
                SELECT COALESCE(MAX(sequence_no),0)+1 FROM messages WHERE session_id=:session
            """), {"session": values["session_id"]})
            await conn.execute(text("""
                INSERT INTO messages
                  (id,session_id,sequence_no,role,content_encrypted,encryption_key_version,
                   modality,source_agent,model_version_id,generation_metadata)
                VALUES (:id,:session,:sequence,:role,:content,:key,'text',:agent,:model,'{}'::jsonb)
            """), {"id": values["message_id"], "session": values["session_id"], "sequence": sequence,
                    "role": values["role"], "content": values["content_encrypted"], "key": values["key_version"],
                    "agent": values.get("source_agent"), "model": values.get("model_version_id")})
            await conn.execute(text("UPDATE sessions SET updated_at=now() WHERE id=:session"),
                               {"session": values["session_id"]})
        return values["message_id"]

    async def update_dialogue_session(self, **values: Any) -> None:
        async with self.engine.begin() as conn:
            result = await conn.execute(text("""
                UPDATE sessions SET interaction_phase=:phase,
                  dialogue_state_encrypted=:dialogue_state,
                  dialogue_state_key_version=:dialogue_key,updated_at=now()
                WHERE id=:session AND patient_id=:patient AND interaction_phase IS NOT NULL
            """), values)
        if result.rowcount != 1:
            raise RepositoryConflictError("Legacy session is read-only")

    async def session_slots(self, session_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT id,slot_key,value_encrypted,completion_status FROM session_slots
                WHERE session_id=:id ORDER BY slot_key
            """), {"id": session_id})).mappings().all()
        return [dict(row) for row in rows]

    async def upsert_session_slot(self, **values: Any) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                INSERT INTO session_slots
                  (id,session_id,slot_key,value_encrypted,encryption_key_version,completion_status,
                   source_message_ids,extraction_model_version_id)
                VALUES (:id,:session,:key,:value,:key_version,:status,:sources,:model)
                ON CONFLICT (session_id,slot_key) DO UPDATE SET
                  value_encrypted=EXCLUDED.value_encrypted,encryption_key_version=EXCLUDED.encryption_key_version,
                  completion_status=EXCLUDED.completion_status,source_message_ids=EXCLUDED.source_message_ids,
                  extraction_model_version_id=EXCLUDED.extraction_model_version_id
            """), {"id": values["slot_id"], "session": values["session_id"], "key": values["slot_key"],
                    "value": values["value_encrypted"], "key_version": values["key_version"],
                    "status": values["completion_status"], "sources": values.get("source_message_ids") or [],
                    "model": values.get("model_version_id")})

    async def start_session(self, session_id: UUID) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE sessions SET status='in_progress',started_at=COALESCE(started_at,now())
                WHERE id=:id AND status='created'
            """), {"id": session_id})

    async def finish_session(self, session_id: UUID, *, status: str, reason: str) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE sessions SET status=:status,completion_reason=:reason,ended_at=now(),
                  started_at=CASE WHEN :status='abandoned' THEN started_at ELSE COALESCE(started_at,now()) END,
                  interaction_phase=CASE WHEN interaction_phase IS NULL THEN NULL ELSE :phase END
                WHERE id=:id AND status IN ('created','in_progress','completed')
            """), {"id": session_id, "status": status, "reason": reason,
                    "phase": "completed" if status == "completed" else "abandoned"})

    async def abandon_inactive_sessions(self, *, cutoff: datetime, now: datetime) -> list[dict[str, Any]]:
        async with self.engine.begin() as conn:
            rows = (await conn.execute(text("""
                WITH stale AS (
                    SELECT id FROM sessions
                    WHERE status IN ('created','in_progress') AND interaction_phase IS NOT NULL
                      AND updated_at < :cutoff
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE sessions AS s
                SET status='abandoned',completion_reason='timeout',ended_at=:now,
                    interaction_phase=CASE WHEN interaction_phase IS NULL THEN NULL ELSE 'abandoned' END
                FROM stale
                WHERE s.id=stale.id
                RETURNING s.id AS session_id,s.patient_id
            """), {"cutoff": cutoff, "now": now})).mappings().all()
        return [dict(row) for row in rows]

    async def add_assessment(self, **values: Any) -> UUID:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                INSERT INTO craving_assessments
                  (id,session_id,instrument_code,instrument_version,phase,attempt_no,answers_encrypted,
                   encryption_key_version,raw_score,scale_min,scale_max)
                VALUES (:id,:session,:code,:version,:phase,:attempt,:answers,:key,:score,:minimum,:maximum)
            """), values)
        return values["id"]

    async def add_intervention(self, **values: Any) -> UUID:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                INSERT INTO interventions
                  (id,session_id,intervention_type,selection_basis_encrypted,content_encrypted,
                   status,encryption_key_version,model_version_id,presentation_order,evidence_refs)
                VALUES (:id,:session,:type,:basis,:content,:status,:key,:model,
                        :presentation_order,CAST(:evidence_refs AS jsonb))
            """), values)
        return values["id"]

    async def session_interventions(self, session_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT * FROM interventions WHERE session_id=:session
                ORDER BY presentation_order NULLS LAST,created_at,id
            """), {"session": session_id})).mappings().all()
        return [dict(row) for row in rows]

    async def session_assessments(self, session_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT * FROM craving_assessments WHERE session_id=:session
                ORDER BY completed_at,id
            """), {"session": session_id})).mappings().all()
        return [dict(row) for row in rows]

    async def ensure_agent_model_version(self, **values: Any) -> UUID:
        component = values["component"]
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext(:key))"), {"key": f"model:{component}"})
            active = (await conn.execute(text("""
                SELECT id,model_name,model_version,prompt_version FROM model_versions
                WHERE component=:component AND is_active LIMIT 1
            """), {"component": component})).mappings().one_or_none()
            if active and active["model_name"] == values["model_name"] and active["model_version"] == values["model_version"] and active["prompt_version"] == values["prompt_version"]:
                return active["id"]
            await conn.execute(text("UPDATE model_versions SET is_active=false WHERE component=:component AND is_active"), {"component": component})
            return (await conn.execute(text("""
                INSERT INTO model_versions
                  (component,model_name,model_version,prompt_version,inference_task,is_active)
                VALUES (:component,:model_name,:model_version,:prompt_version,'generative',true)
                ON CONFLICT (component,model_name,model_version) DO UPDATE SET
                  prompt_version=EXCLUDED.prompt_version,is_active=true RETURNING id
            """), values)).scalar_one()

    async def ensure_rule_model_version(self, *, component: str, rule_version: str) -> UUID:
        async with self.engine.begin() as conn:
            return (await conn.execute(text("""
                INSERT INTO model_versions
                  (component,model_name,model_version,inference_task,output_schema,config,is_active)
                VALUES (:component,'deterministic-state-inference',:rule_version,
                        'hybrid','{}'::jsonb,'{}'::jsonb,false)
                ON CONFLICT (component,model_name,model_version) DO UPDATE
                  SET model_version=EXCLUDED.model_version
                RETURNING id
            """), {"component": component, "rule_version": rule_version})).scalar_one()

    async def create_report_job(self, **values: Any) -> dict[str, Any]:
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext(:key))"),
                               {"key": f"report:{values['session_id']}"})
            existing = (await conn.execute(text("""
                SELECT * FROM session_reports WHERE session_id=:session ORDER BY version DESC LIMIT 1
            """), {"session": values["session_id"]})).mappings().one_or_none()
            if existing and existing["status"] in ("generating","ready"):
                return dict(existing)
            version = int(existing["version"] + 1) if existing else 1
            row = (await conn.execute(text("""
                INSERT INTO session_reports (id,session_id,version,status,evidence_refs,model_version_id)
                VALUES (:id,:session,:version,'generating',CAST(:evidence AS jsonb),:model) RETURNING *
            """), {"id": values["report_id"], "session": values["session_id"], "version": version,
                    "evidence": values["evidence_json"], "model": values.get("model_version_id")})).mappings().one()
        return dict(row)

    async def finish_report_job(self, **values: Any) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE session_reports SET status=:status,content_encrypted=:content,
                  encryption_key_version=:key,failure_reason=:failure,generated_at=CASE WHEN :status='ready' THEN now() ELSE NULL END
                WHERE id=:id
            """), values)

    async def session_reports(self, patient_id: UUID, session_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT r.* FROM session_reports r JOIN sessions s ON s.id=r.session_id
                WHERE r.session_id=:session AND s.patient_id=:patient ORDER BY r.version DESC
            """), {"session": session_id, "patient": patient_id})).mappings().all()
        return [dict(row) for row in rows]

    async def add_memory_snapshot(self, **values: Any) -> UUID:
        async with self.engine.begin() as conn:
            await conn.execute(text("UPDATE memory_snapshots SET is_current=false WHERE patient_id=:patient"), values)
            version = await conn.scalar(text("SELECT COALESCE(MAX(version),0)+1 FROM memory_snapshots WHERE patient_id=:patient"), values)
            await conn.execute(text("""
                INSERT INTO memory_snapshots
                  (id,patient_id,source_session_id,version,summary_encrypted,encryption_key_version,model_version_id)
                VALUES (:id,:patient,:session,:version,:summary,:key,:model)
            """), {**values, "version": version})
        return values["id"]

    async def pending_report_jobs(self) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT r.id,r.session_id,s.patient_id,r.evidence_refs
                FROM session_reports r JOIN sessions s ON s.id=r.session_id
                WHERE r.status='generating' AND s.interaction_phase IS NOT NULL
                ORDER BY r.created_at
            """))).mappings().all()
        return [dict(row) for row in rows]

    async def inference_evidence(
        self, patient_id: UUID, session_id: UUID | None, *, since: datetime | None = None
    ) -> dict[str, Any]:
        params = {"patient": patient_id, "session": session_id, "since": since}
        async with self.engine.connect() as conn:
            prediction = (await conn.execute(text("""
                SELECT p.id,p.predicted_class_index,p.predicted_class_code,
                       p.predicted_class_probability,p.class_probabilities,p.predicted_at,
                       p.quality_gate_passed,m.output_schema
                FROM craving_predictions p JOIN model_versions m ON m.id=p.model_version_id
                LEFT JOIN craving_alerts a ON a.trigger_prediction_id=p.id
                LEFT JOIN sessions s ON s.trigger_alert_id=a.id
                WHERE p.patient_id=:patient AND p.quality_gate_passed
                  AND (:session IS NULL OR s.id=:session OR s.id IS NULL)
                  AND (:since IS NULL OR p.predicted_at>=:since)
                ORDER BY (s.id=:session) DESC NULLS LAST,p.predicted_at DESC,p.id DESC LIMIT 1
            """), params)).mappings().one_or_none()
            assessments = (await conn.execute(text("""
                SELECT a.id,a.raw_score,a.scale_min,a.scale_max,a.completed_at
                FROM craving_assessments a JOIN sessions s ON s.id=a.session_id
                WHERE s.patient_id=:patient AND (:session IS NULL OR s.id=:session)
                  AND (:since IS NULL OR a.completed_at>=:since)
                ORDER BY a.completed_at,a.id
            """), params)).mappings().all()
            alerts = (await conn.execute(text("""
                SELECT id,status,triggered_at,notified_at,acknowledged_at,dismissed_at
                FROM craving_alerts WHERE patient_id=:patient
                  AND (:since IS NULL OR triggered_at>=:since)
                ORDER BY triggered_at,id
            """), params)).mappings().all()
            interventions = (await conn.execute(text("""
                SELECT i.id,i.intervention_type,i.status,i.presentation_order,i.created_at
                FROM interventions i JOIN sessions s ON s.id=i.session_id
                WHERE s.patient_id=:patient AND (:session IS NULL OR s.id=:session)
                  AND (:since IS NULL OR i.created_at>=:since)
                ORDER BY i.created_at,i.id
            """), params)).mappings().all()
            messages = (await conn.execute(text("""
                SELECT m.id,m.role,m.created_at FROM messages m JOIN sessions s ON s.id=m.session_id
                WHERE s.patient_id=:patient AND (:session IS NULL OR s.id=:session)
                  AND (:since IS NULL OR m.created_at>=:since)
                ORDER BY m.created_at,m.id
            """), params)).mappings().all()
        return {
            "prediction": dict(prediction) if prediction else None,
            "assessments": [dict(row) for row in assessments],
            "alerts": [dict(row) for row in alerts],
            "interventions": [dict(row) for row in interventions],
            "messages": [dict(row) for row in messages],
        }

    async def add_state_inference(self, **values: Any) -> dict[str, Any]:
        import json
        async with self.engine.begin() as conn:
            row = (await conn.execute(text("""
                INSERT INTO state_inferences
                  (id,patient_id,session_id,trigger_prediction_id,inference_scope,state_class,
                   confidence,evidence_refs,payload_encrypted,summary_encrypted,
                   encryption_key_version,rule_version,summary_model_version_id,summary_status)
                VALUES (:id,:patient_id,:session_id,:trigger_prediction_id,:scope,:state_class,
                   :confidence,CAST(:evidence_refs AS jsonb),:payload_encrypted,:summary_encrypted,
                   :key_version,:rule_version,:summary_model_version_id,:summary_status)
                ON CONFLICT DO NOTHING
                RETURNING *
            """), {**values, "evidence_refs": json.dumps(values["evidence_refs"], separators=(",", ":"))})).mappings().one_or_none()
            if row is None:
                row = (await conn.execute(text("""
                    SELECT * FROM state_inferences
                    WHERE patient_id=:patient_id AND inference_scope=:scope
                      AND evidence_refs->>'eventKey'=:event_key
                    LIMIT 1
                """), {"patient_id": values["patient_id"], "scope": values["scope"],
                         "event_key": values["evidence_refs"]["eventKey"]})).mappings().one()
        return dict(row)

    async def state_inferences(self, patient_id: UUID, session_id: UUID | None = None) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT * FROM state_inferences WHERE patient_id=:patient
                  AND (:session IS NULL OR session_id=:session)
                ORDER BY created_at,id
            """), {"patient": patient_id, "session": session_id})).mappings().all()
        return [dict(row) for row in rows]

    async def dashboard_rows(self, patient_id: UUID, since: datetime) -> dict[str, list[dict[str, Any]]]:
        params = {"patient": patient_id, "since": since}
        async with self.engine.connect() as conn:
            predictions = (await conn.execute(text("""
                SELECT p.id,p.sensor_recording_id,p.predicted_class_index,p.predicted_class_code,
                       p.predicted_class_probability,p.predicted_at,p.quality_gate_passed,m.output_schema
                       ,(r.id IS NOT NULL AND 'ppg'=ANY(r.modalities) AND r.deleted_at IS NULL) AS ppg_preview_available
                FROM craving_predictions p JOIN model_versions m ON m.id=p.model_version_id
                LEFT JOIN sensor_recordings r ON r.id=p.sensor_recording_id AND r.patient_id=p.patient_id
                WHERE p.patient_id=:patient AND p.predicted_at>=:since
                ORDER BY p.predicted_at,p.id
            """), params)).mappings().all()
            assessments = (await conn.execute(text("""
                SELECT a.id,a.session_id,a.instrument_code,a.raw_score,a.scale_min,a.scale_max,a.completed_at
                FROM craving_assessments a JOIN sessions s ON s.id=a.session_id
                WHERE s.patient_id=:patient AND a.completed_at>=:since
                ORDER BY a.completed_at,a.id
            """), params)).mappings().all()
            alerts = (await conn.execute(text("""
                SELECT id,trigger_prediction_id,status,triggered_at,notified_at,acknowledged_at,dismissed_at
                FROM craving_alerts WHERE patient_id=:patient AND triggered_at>=:since
                ORDER BY triggered_at,id
            """), params)).mappings().all()
            sessions = (await conn.execute(text("""
                SELECT id,trigger_alert_id,status,interaction_phase,created_at,started_at,ended_at
                FROM sessions WHERE patient_id=:patient AND created_at>=:since
                ORDER BY created_at,id
            """), params)).mappings().all()
            interventions = (await conn.execute(text("""
                SELECT i.id,i.session_id,i.intervention_type,i.status,i.presentation_order,i.created_at
                FROM interventions i JOIN sessions s ON s.id=i.session_id
                WHERE s.patient_id=:patient AND i.created_at>=:since
                ORDER BY i.created_at,i.id
            """), params)).mappings().all()
            inferences = (await conn.execute(text("""
                SELECT id,session_id,trigger_prediction_id,inference_scope,state_class,confidence,
                       summary_status,summary_encrypted,created_at
                FROM state_inferences WHERE patient_id=:patient AND created_at>=:since
                ORDER BY created_at,id
            """), params)).mappings().all()
            reports = (await conn.execute(text("""
                SELECT r.id,r.session_id,r.status,r.updated_at
                FROM session_reports r JOIN sessions s ON s.id=r.session_id
                WHERE s.patient_id=:patient AND r.updated_at>=:since
                ORDER BY r.updated_at,r.id
            """), params)).mappings().all()
        return {name: [dict(row) for row in rows] for name, rows in {
            "predictions": predictions, "assessments": assessments, "alerts": alerts,
            "sessions": sessions, "interventions": interventions,
            "inferences": inferences, "reports": reports,
        }.items()}

    async def prediction_sensor(self, patient_id: UUID, prediction_id: UUID) -> dict[str, Any] | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT p.id AS prediction_id,p.window_started_at,p.window_ended_at,
                       r.id AS recording_id,r.storage_uri,r.sample_rates
                FROM craving_predictions p JOIN sensor_recordings r
                  ON r.id=p.sensor_recording_id AND r.patient_id=p.patient_id
                WHERE p.id=:prediction AND p.patient_id=:patient AND r.deleted_at IS NULL
            """), {"prediction": prediction_id, "patient": patient_id})).mappings().one_or_none()
        return dict(row) if row else None

    @staticmethod
    async def _insert_consent(conn: Any, user_id: UUID, consent: ConsentInput) -> Any:
        values = consent.model_dump()
        return (await conn.execute(text("""
            INSERT INTO consent_snapshots
              (user_id,tos,privacy,sensitive,biosignal,voice,ai_analysis,notification,
               report_generation,camera_rppg,face_video_retention,tos_version,privacy_version,consent_form_version)
            VALUES (:user_id,:tos,:privacy,:sensitive,:biosignal,false,:ai_analysis,:notification,
                    :report_generation,:camera_rppg,:face_video_retention,:tos_version,:privacy_version,:consent_form_version)
            RETURNING *
        """), {"user_id": user_id, **values})).mappings().one()

    @staticmethod
    async def _insert_auth(conn: Any, session: AuthSessionRecord, device: dict[str, Any]) -> None:
        import json
        await conn.execute(text("""
            INSERT INTO auth_sessions
              (id,user_id,token_family_id,refresh_token_hash,parent_session_id,
               device_metadata,expires_at)
            VALUES (:id,:user_id,:family,:token_hash,:parent,CAST(:device AS jsonb),:expires_at)
        """), {"id": session.id, "user_id": session.user_id,
                "family": session.token_family_id, "token_hash": session.refresh_token_hash,
                "parent": session.parent_session_id, "device": json.dumps(device, separators=(",", ":")),
                "expires_at": session.expires_at})
