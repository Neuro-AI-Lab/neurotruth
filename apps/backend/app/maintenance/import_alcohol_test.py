from __future__ import annotations

import argparse
import asyncio
import bisect
import csv
import json
import math
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence
from uuid import UUID, uuid4, uuid5

from sqlalchemy import text

from app.core.config import SecuritySettings
from app.core.security.crypto import AesGcmKeyring, aad_for
from app.core.security.passwords import hash_password, verify_password
from app.ml.craving.latency import LatencyRecorder
from app.ml.craving.model import EXPECTED_WEIGHTS_SHA256, CravingModel, _default_model_path
from app.models.records import ConsentRecord, UserRecord
from app.repositories.postgres import SqlAlchemyV25Repository
from app.schemas.auth import ConsentInput
from app.services.prediction import PatientPredictionHub
from app.services.sensor import SensorService
from app.storage.sensor import EncryptedSensorStorage


WINDOW_MS = 20_000
STRIDE_MS = 10_000
ACCOUNT_NAMESPACE = UUID("a561e22b-2a92-442f-97d6-20bb71085f50")
WINDOW_NAMESPACE = UUID("f8b8c80d-1876-483d-a1d5-a4b670ad383b")
START_ACTION = "alcohol_test_import.started"
COMPLETE_ACTION = "alcohol_test_import.completed"
REQUIRED_HEADERS = {
    "timestamp": "_Timestamp_Unix_CAL",
    "ppg": "_PPG_A13_CAL",
    "gsr": "_GSR_Skin_Conductance_CAL",
}


@dataclass(frozen=True)
class SourceRecording:
    subject_visit: str
    base_subject: str
    visit: str
    path: Path
    relative_path: str


@dataclass(frozen=True)
class SignalData:
    timestamps_ms: list[float]
    ppg: list[float]
    gsr: list[float]


@dataclass(frozen=True)
class SignalWindow:
    sequence: int
    source_start_ms: int
    source_end_ms: int
    timestamps_ms: list[float]
    ppg: list[float]
    gsr: list[float]


@dataclass(frozen=True)
class ImportRun:
    run_id: str
    anchor_ms: int
    completed: bool = False


def _subject_parts(subject_visit: str) -> tuple[str, str]:
    for visit in ("V1", "V2"):
        suffix = f"_{visit}"
        if subject_visit.endswith(suffix):
            return subject_visit[: -len(suffix)], visit
    raise ValueError(f"invalid_subject_visit:{subject_visit}")


def discover_recordings(data_root: Path, subject_visit: str | None = None) -> tuple[list[SourceRecording], list[str]]:
    recordings: list[SourceRecording] = []
    failures: list[str] = []
    subjects = [data_root / subject_visit] if subject_visit else sorted(
        (path for path in data_root.iterdir() if path.is_dir()), key=lambda path: path.name
    )
    for subject_path in subjects:
        name = subject_path.name
        try:
            base_subject, visit = _subject_parts(name)
            sensor_root = subject_path / "ECG_PPG_GSR"
            marker = f"{name}G_"
            matches = [
                path for path in sensor_root.iterdir()
                if path.is_dir() and marker in path.name
            ]
            if len(matches) != 1:
                raise ValueError(f"expected_one_g_directory:found_{len(matches)}")
            csv_files = sorted(path for path in matches[0].iterdir() if path.is_file() and path.suffix.lower() == ".csv")
            if len(csv_files) != 1:
                raise ValueError(f"expected_one_g_csv:found_{len(csv_files)}")
            recordings.append(SourceRecording(
                subject_visit=name,
                base_subject=base_subject,
                visit=visit,
                path=csv_files[0],
                relative_path=csv_files[0].relative_to(data_root).as_posix(),
            ))
        except (OSError, ValueError) as exc:
            failures.append(f"{name}:{exc}")
    return recordings, failures


def _header_indices(header: list[str]) -> dict[str, int]:
    indices: dict[str, int] = {}
    for key, suffix in REQUIRED_HEADERS.items():
        matches = [index for index, value in enumerate(header) if value.strip().endswith(suffix)]
        if len(matches) != 1:
            raise ValueError(f"required_header_{key}:found_{len(matches)}")
        indices[key] = matches[0]
    return indices


def read_recording(path: Path) -> SignalData:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle, delimiter="\t")
        first = next(reader, None)
        if first and len(first) == 1 and first[0].strip().lower().startswith("sep="):
            header = next(reader, None)
        else:
            header = first
        if not header:
            raise ValueError("missing_header")
        indices = _header_indices(header)
        next(reader, None)  # Units row.
        timestamps: list[float] = []
        ppg: list[float] = []
        gsr: list[float] = []
        required_width = max(indices.values()) + 1
        for row_number, row in enumerate(reader, start=4):
            if len(row) < required_width:
                raise ValueError(f"short_row:{row_number}")
            try:
                values = [float(row[indices[key]]) for key in ("timestamp", "ppg", "gsr")]
            except ValueError as exc:
                raise ValueError(f"invalid_number:{row_number}") from exc
            if not all(math.isfinite(value) for value in values):
                raise ValueError(f"non_finite_value:{row_number}")
            timestamps.append(values[0])
            ppg.append(values[1])
            gsr.append(values[2])
    if len(timestamps) < 2 or any(right <= left for left, right in zip(timestamps, timestamps[1:])):
        raise ValueError("timestamps_not_strictly_increasing")
    if timestamps[-1] - timestamps[0] < WINDOW_MS:
        raise ValueError("recording_shorter_than_20_seconds")
    return SignalData(timestamps, ppg, gsr)


def iter_windows(data: SignalData) -> Iterable[SignalWindow]:
    start = data.timestamps_ms[0]
    sequence = 1
    while start + WINDOW_MS <= data.timestamps_ms[-1]:
        end = start + WINDOW_MS
        left = bisect.bisect_left(data.timestamps_ms, start)
        right = bisect.bisect_left(data.timestamps_ms, end)
        yield SignalWindow(
            sequence=sequence, source_start_ms=round(start), source_end_ms=round(end),
            timestamps_ms=data.timestamps_ms[left:right], ppg=data.ppg[left:right],
            gsr=data.gsr[left:right],
        )
        start += STRIDE_MS
        sequence += 1


def deterministic_window_id(run_id: str, recording: SourceRecording, window: SignalWindow) -> UUID:
    key = f"{run_id}:{recording.subject_visit}:{window.source_start_ms}:{window.source_end_ms}"
    return uuid5(WINDOW_NAMESPACE, key)


def window_payload(run: ImportRun, recording: SourceRecording, window: SignalWindow,
                   session_start_ms: int, last_end_ms: int) -> dict[str, Any]:
    offset_ms = run.anchor_ms - last_end_ms
    start_ms = window.source_start_ms + offset_ms
    end_ms = window.source_end_ms + offset_ms
    samples: list[dict[str, Any]] = []
    for stamp, ppg, gsr in zip(window.timestamps_ms, window.ppg, window.gsr):
        rebased = stamp + offset_ms
        samples.append({"sensor": "PPG_GREEN", "timestampMs": rebased, "value": ppg})
        samples.append({"sensor": "EDA", "timestampMs": rebased, "value": gsr})
    return {
        "clientWindowId": str(deterministic_window_id(run.run_id, recording, window)),
        "sessionStartedAtMs": session_start_ms + offset_ms,
        "sequence": window.sequence,
        "sentAtMs": end_ms,
        "windowStartMs": start_ms,
        "windowEndMs": end_ms,
        "windowMs": WINDOW_MS,
        "samples": samples,
        "sampleRates": {"ppg": 51.2, "eda": 51.2},
        "deviceInfo": {
            "dataset": "Alcohol_Test",
            "subjectVisit": recording.subject_visit,
            "baseSubject": recording.base_subject,
            "visit": recording.visit,
            "sourceRelativePath": recording.relative_path,
            "sourceWindowStartMs": window.source_start_ms,
            "sourceWindowEndMs": window.source_end_ms,
            "timestampOffsetMs": offset_ms,
            "importRunId": run.run_id,
        },
    }


def _consent() -> ConsentInput:
    return ConsentInput(
        tos=True, privacy=True, sensitive=True, biosignal=True, ai_analysis=True,
        notification=False, voice=False, report_generation=False, camera_rppg=False,
        face_video_retention=False, tos_version="alcohol-test-v1",
        privacy_version="alcohol-test-v1", consent_form_version="alcohol-test-v1",
    )


async def ensure_test_account(
    repository: Any, keyring: AesGcmKeyring, recording: SourceRecording, password: str, *,
    password_hasher: Callable[[str], str] = hash_password,
    password_verifier: Callable[[str, str], bool] = verify_password,
) -> tuple[UserRecord, ConsentRecord, bool]:
    email = f"alcohol-test+{recording.subject_visit.lower()}@neurotruth.local"
    existing = await repository.user_by_email(email)
    if existing is not None:
        consent = await repository.current_consent(existing.id)
        valid = (
            existing.role == "patient" and existing.status == "active"
            and password_verifier(password, existing.password_hash)
            and consent is not None and consent.tos and consent.privacy and consent.sensitive
            and consent.biosignal and consent.ai_analysis and not consent.notification
            and not any((consent.voice, consent.report_generation, consent.camera_rppg, consent.face_video_retention))
        )
        if not valid:
            raise ValueError(f"existing_account_contract_mismatch:{email}")
        return existing, consent, False
    user_id = uuid5(ACCOUNT_NAMESPACE, recording.subject_visit)
    name = f"Alcohol Test {recording.subject_visit}"
    encrypted_name = keyring.encrypt(
        name.encode("utf-8"),
        aad=aad_for(table="patient_profiles", column="name_encrypted", patient_id=str(user_id), record_id=str(user_id)),
    ).pack()
    user = await repository.create_patient(
        user_id=user_id, email=email, password_hash=password_hasher(password),
        name_encrypted=encrypted_name, encryption_key_version=keyring.current_key_id,
        birth_year=None, gender=None, consent=_consent(),
    )
    consent = await repository.current_consent(user.id)
    if consent is None:
        raise RuntimeError("created_account_missing_consent")
    await repository.audit(
        actor_id=user.id, actor_role="patient", action="patient.signup",
        resource_type="user", resource_id=user.id,
        metadata={"source": "Alcohol_Test", "subjectVisit": recording.subject_visit},
    )
    return user, consent, True


class AuditRunStore:
    def __init__(self, repository: Any) -> None:
        self.repository = repository

    async def resolve(self, *, run_id: str | None, new_run: bool, anchor_ms: int) -> ImportRun:
        if run_id is not None:
            UUID(run_id)
        query = """
            SELECT metadata FROM audit_logs
            WHERE action=:action AND metadata->>'runId'=COALESCE(:run_id,metadata->>'runId')
            ORDER BY created_at DESC LIMIT 1
        """
        async with self.repository.engine.connect() as connection:
            row = (await connection.execute(text(query), {"action": START_ACTION, "run_id": run_id})).mappings().one_or_none()
        if row is not None and not new_run:
            metadata = dict(row["metadata"] or {})
            resolved = ImportRun(str(metadata["runId"]), int(metadata["anchorMs"]))
            async with self.repository.engine.connect() as connection:
                completed = await connection.scalar(text("""
                    SELECT EXISTS(SELECT 1 FROM audit_logs
                      WHERE action=:action AND metadata->>'runId'=:run_id)
                """), {"action": COMPLETE_ACTION, "run_id": resolved.run_id})
            return ImportRun(resolved.run_id, resolved.anchor_ms, bool(completed))
        if row is not None and new_run and run_id is not None:
            raise ValueError("run_id_already_exists")
        resolved = ImportRun(run_id or str(uuid4()), anchor_ms)
        await self.repository.audit(
            actor_id=None, actor_role="system", action=START_ACTION,
            resource_type="sensor_recording", metadata={"runId": resolved.run_id, "anchorMs": resolved.anchor_ms},
        )
        return resolved

    async def complete(self, run: ImportRun, summary: dict[str, Any]) -> None:
        await self.repository.audit(
            actor_id=None, actor_role="system", action=COMPLETE_ACTION,
            resource_type="sensor_recording", metadata={"runId": run.run_id, **summary},
        )


class AlcoholTestImporter:
    def __init__(self, repository: Any, keyring: AesGcmKeyring, sensor_service: Any, run_store: AuditRunStore) -> None:
        self.repository = repository
        self.keyring = keyring
        self.sensor_service = sensor_service
        self.run_store = run_store

    async def run(
        self, *, data_root: Path, output_root: Path, password: str, subject_visit: str | None,
        limit_windows: int | None, run_id: str | None, new_run: bool, anchor_ms: int,
    ) -> dict[str, Any]:
        recordings, failures = discover_recordings(data_root, subject_visit)
        run = await self.run_store.resolve(run_id=run_id, new_run=new_run, anchor_ms=anchor_ms)
        if run.completed:
            return {"status": "already_complete", "runId": run.run_id, "anchorMs": run.anchor_ms}
        output_dir = output_root / run.run_id
        output_dir.mkdir(parents=True, exist_ok=True)
        accounts: list[dict[str, Any]] = []
        predictions: list[dict[str, Any]] = []
        remaining = limit_windows
        for recording in recordings:
            try:
                data = read_recording(recording.path)
            except (OSError, ValueError) as exc:
                failures.append(f"{recording.subject_visit}:{type(exc).__name__}:{exc}")
                continue
            windows = list(iter_windows(data))
            if not windows:
                failures.append(f"{recording.subject_visit}:no_complete_windows")
                continue
            user, consent, created = await ensure_test_account(
                self.repository, self.keyring, recording, password
            )
            accounts.append({"subjectVisit": recording.subject_visit, "email": user.email, "patientId": str(user.id), "created": created})
            for window in windows:
                if remaining is not None and remaining <= 0:
                    break
                if not window.timestamps_ms:
                    failures.append(f"{recording.subject_visit}:empty_window:{window.sequence}")
                    continue
                payload = window_payload(
                    run, recording, window, round(data.timestamps_ms[0]),
                    windows[-1].source_end_ms,
                )
                result = await self.sensor_service.ingest(
                    patient_id=user.id, consent_snapshot_id=consent.id,
                    ai_analysis_allowed=True, notification_allowed=False, payload=payload,
                )
                predictions.append({
                    "runId": run.run_id, "subjectVisit": recording.subject_visit,
                    "sequence": window.sequence, "clientWindowId": payload["clientWindowId"],
                    "sourceWindowStartMs": window.source_start_ms, "sourceWindowEndMs": window.source_end_ms,
                    "windowStartMs": payload["windowStartMs"], "windowEndMs": payload["windowEndMs"],
                    "class": result.get("class"), "confidence": result.get("confidence"),
                    "cravingProbability": result.get("cravingProbability"),
                    "recordingId": result.get("recordingId"), "predictionId": result.get("predictionId"),
                })
                if remaining is not None:
                    remaining -= 1
            if remaining is not None and remaining <= 0:
                break
        summary = self._summary(run, recordings, accounts, predictions, failures)
        self._write_reports(output_dir, accounts, predictions, summary)
        if limit_windows is None and subject_visit is None:
            await self.run_store.complete(run, summary)
        return summary

    @staticmethod
    def _summary(run: ImportRun, recordings: list[SourceRecording], accounts: list[dict[str, Any]], predictions: list[dict[str, Any]], failures: list[str]) -> dict[str, Any]:
        probabilities = [float(row["cravingProbability"]) for row in predictions if row.get("cravingProbability") is not None]
        subjects: dict[str, dict[str, Any]] = {}
        for name in sorted({row["subjectVisit"] for row in predictions}):
            rows = [row for row in predictions if row["subjectVisit"] == name]
            subject_probabilities = [float(row["cravingProbability"]) for row in rows if row.get("cravingProbability") is not None]
            subjects[name] = {
                "windowCount": len(rows),
                "lowCount": sum(row.get("class") == 0 for row in rows),
                "highCount": sum(row.get("class") == 1 for row in rows),
                "meanCravingProbability": sum(subject_probabilities) / len(subject_probabilities) if subject_probabilities else None,
                "maxCravingProbability": max(subject_probabilities) if subject_probabilities else None,
            }
        return {
            "status": "ok" if not failures else "partial",
            "runId": run.run_id, "anchorMs": run.anchor_ms,
            "sourceCount": len(recordings), "accountCount": len(accounts),
            "windowCount": len(predictions),
            "lowCount": sum(row.get("class") == 0 for row in predictions),
            "highCount": sum(row.get("class") == 1 for row in predictions),
            "meanCravingProbability": sum(probabilities) / len(probabilities) if probabilities else None,
            "maxCravingProbability": max(probabilities) if probabilities else None,
            "subjects": subjects, "failureCount": len(failures), "failures": failures,
        }

    @staticmethod
    def _write_reports(output_dir: Path, accounts: list[dict[str, Any]], predictions: list[dict[str, Any]], summary: dict[str, Any]) -> None:
        reports = (
            ("accounts.csv", accounts, ("subjectVisit", "email", "patientId", "created")),
            ("predictions.csv", predictions, ("runId", "subjectVisit", "sequence", "clientWindowId", "sourceWindowStartMs", "sourceWindowEndMs", "windowStartMs", "windowEndMs", "class", "confidence", "cravingProbability", "recordingId", "predictionId")),
        )
        for name, rows, fields in reports:
            target = output_dir / name
            with target.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
        (output_dir / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
        )


class _DirectPredictor:
    inference_task = "binary_classification"
    output_schema = {"predictionSchema": "binary-craving-v1", "classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "high"}]}

    def __init__(self, model: CravingModel) -> None:
        self.model = model
        self.ready = model.ready
        self.model_name = model.model_name
        self.model_version = os.getenv("CRAVING_MODEL_VERSION", model.model_version)
        self.artifact_uri = model.safe_artifact_uri
        self.registration_config = model.registration_config

    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self.model.predict, payload)


def validate_dataset(data_root: Path, subject_visit: str | None = None) -> dict[str, Any]:
    recordings, failures = discover_recordings(data_root, subject_visit)
    window_count = 0
    for recording in recordings:
        try:
            windows = list(iter_windows(read_recording(recording.path)))
            window_count += len(windows)
            failures.extend(f"{recording.subject_visit}:empty_window:{window.sequence}" for window in windows if not window.timestamps_ms)
        except Exception as exc:
            failures.append(f"{recording.subject_visit}:{type(exc).__name__}:{exc}")
    return {"status": "ok" if not failures else "failed", "sourceCount": len(recordings), "windowCount": window_count, "failureCount": len(failures), "failures": failures}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Import Alcohol_Test G recordings through the production craving pipeline.")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--subject-visit")
    parser.add_argument("--limit-windows", type=int)
    parser.add_argument("--run-id")
    parser.add_argument("--new-run", action="store_true")
    return parser


def _roots() -> tuple[Path, Path]:
    backend = Path(__file__).resolve().parents[2]
    champion = backend.parents[2]
    return (
        Path(os.getenv("ALCOHOL_TEST_DATA_ROOT", champion / "Alcohol_Test" / "data")),
        Path(os.getenv("ALCOHOL_TEST_OUTPUT_ROOT", champion / "Alcohol_Test" / "output")),
    )


async def _run_import(args: argparse.Namespace, data_root: Path, output_root: Path) -> dict[str, Any]:
    password = os.getenv("ALCOHOL_TEST_ACCOUNT_PASSWORD", "")
    if not 12 <= len(password) <= 1024:
        raise ValueError("ALCOHOL_TEST_ACCOUNT_PASSWORD must contain between 12 and 1024 characters")
    settings = SecuritySettings()
    repository = SqlAlchemyV25Repository(settings.database_url)
    try:
        backend = Path(__file__).resolve().parents[2]
        model_path = Path(os.getenv("CRAVING_MODEL_PATH", _default_model_path(backend)))
        model = CravingModel(
            model_path, Path(os.getenv("CRAVING_MODEL_METADATA_PATH", model_path.with_name("model_metadata.json"))),
            expected_sha256=os.getenv("CRAVING_MODEL_SHA256", EXPECTED_WEIGHTS_SHA256),
            requested_device=os.getenv("CRAVING_INFERENCE_DEVICE", "auto"),
        )
        model.load()
        service = SensorService(
            repository, EncryptedSensorStorage(settings.sensor_storage_root, settings.keyring()),
            _DirectPredictor(model), lambda *_: (_ for _ in ()).throw(AssertionError("alerts_disabled")),
            PatientPredictionHub(), LatencyRecorder(),
        )
        importer = AlcoholTestImporter(repository, settings.keyring(), service, AuditRunStore(repository))
        return await importer.run(
            data_root=data_root, output_root=output_root, password=password,
            subject_visit=args.subject_visit, limit_windows=args.limit_windows,
            run_id=args.run_id, new_run=args.new_run,
            anchor_ms=int(datetime.now(timezone.utc).timestamp() * 1000),
        )
    finally:
        await repository.close()


def run_cli(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.limit_windows is not None and args.limit_windows < 1:
        print("--limit-windows must be positive", file=sys.stderr)
        return 2
    data_root, output_root = _roots()
    try:
        report = validate_dataset(data_root, args.subject_visit) if args.validate_only else asyncio.run(
            _run_import(args, data_root, output_root)
        )
    except Exception as exc:
        print(json.dumps({"status": "failed", "errorCode": type(exc).__name__}), file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
    return 0 if report.get("status") in {"ok", "already_complete"} else 1


def main() -> None:
    raise SystemExit(run_cli())


if __name__ == "__main__":
    main()
