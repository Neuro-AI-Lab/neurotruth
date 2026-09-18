from __future__ import annotations

import asyncio
import base64
import csv
import json
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

import pytest

import app.maintenance.import_alcohol_test as batch
from app.core.security.crypto import AesGcmKeyring
from app.models.records import ConsentRecord, UserRecord


PASSWORD = "alcohol-test-password"


def _recording(root: Path, subject: str = "1_1_001_V1", *, extended: bool = False) -> Path:
    selected = root / subject / "ECG_PPG_GSR" / f"2025-03-13_09.11.42_{subject}G_SD_Session1"
    selected.mkdir(parents=True)
    (root / subject / "ECG_PPG_GSR" / f"2025_{subject}ECG_SD_Session1").mkdir()
    rejected = root / subject / "ECG_PPG_GSR_Test" / f"2025_{subject}GT_SD_Session1"
    rejected.mkdir(parents=True)
    (rejected / "rejected.csv").write_text("unused", encoding="utf-8")
    path = selected / f"{subject}G_Session1_Calibrated_SD.csv"
    if extended:
        header = ["unused"] * 15
        header[0] = f"{subject}_Timestamp_Unix_CAL"
        header[6] = f"{subject}_GSR_Skin_Conductance_CAL"
        header[14] = f"{subject}_PPG_A13_CAL"
    else:
        header = [
            f"{subject}_Timestamp_Unix_CAL",
            f"{subject}_GSR_Range_CAL",
            f"{subject}_GSR_Skin_Conductance_CAL",
            f"{subject}_GSR_Skin_Resistance_CAL",
            f"{subject}_PPG_A13_CAL",
        ]
    rows = []
    for index in range(41):
        if extended:
            row = [str(index)] * 15
            row[0], row[6], row[14] = str(1_700_000_000_000 + index * 1000), str(index / 10), str(index * 2)
        else:
            row = [str(1_700_000_000_000 + index * 1000), "0", str(index / 10), "100", str(index * 2)]
        rows.append(row)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["sep=\t"])
        writer.writerow(header)
        writer.writerow(["unit"] * len(header))
        writer.writerows(rows)
    return path


def _remove_second_window(path: Path) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    path.write_text(
        "\n".join(lines[:3] + [line for index, line in enumerate(lines[3:]) if index < 10 or index >= 31]) + "\n",
        encoding="utf-8",
    )


def _keyring() -> AesGcmKeyring:
    return AesGcmKeyring.from_config(
        "v1:" + base64.b64encode(b"k" * 32).decode(), "v1"
    )


def _consent(user_id: UUID) -> ConsentRecord:
    return ConsentRecord(
        id=UUID("00000000-0000-0000-0000-000000000099"), user_id=user_id,
        tos=True, privacy=True, sensitive=True, biosignal=True, ai_analysis=True,
        notification=False, report_generation=False, tos_version="alcohol-test-v1",
        privacy_version="alcohol-test-v1", consent_form_version="alcohol-test-v1",
        collected_at=datetime.now(timezone.utc),
    )


def _user(subject: str = "1_1_001_V1") -> UserRecord:
    return UserRecord(
        id=UUID("00000000-0000-0000-0000-000000000088"),
        email=f"alcohol-test+{subject.lower()}@neurotruth.local",
        password_hash="hashed", role="patient", status="active",
        must_change_password=False, created_at=datetime.now(timezone.utc),
    )


def test_discovery_uses_only_exact_g_token_under_production_sensor_folder(tmp_path: Path) -> None:
    selected = _recording(tmp_path)
    recordings, failures = batch.discover_recordings(tmp_path)

    assert failures == []
    assert [item.path for item in recordings] == [selected]
    assert recordings[0].base_subject == "1_1_001"
    assert recordings[0].visit == "V1"
    assert "ECG_PPG_GSR_Test" not in recordings[0].relative_path

    duplicate = selected.parent.parent / "another_1_1_001_V1G_SD_Session2"
    duplicate.mkdir()
    recordings, failures = batch.discover_recordings(tmp_path)
    assert recordings == []
    assert "expected_one_g_directory:found_2" in failures[0]


@pytest.mark.parametrize("extended", [False, True])
def test_tsv_parser_resolves_required_columns_by_suffix(extended: bool, tmp_path: Path) -> None:
    path = _recording(tmp_path, extended=extended)
    data = batch.read_recording(path)

    assert len(data.timestamps_ms) == 41
    assert data.timestamps_ms[0] == 1_700_000_000_000
    assert data.timestamps_ms[-1] == 1_700_000_040_000
    assert data.ppg[3] == 6
    assert data.gsr[3] == pytest.approx(0.3)


def test_tsv_parser_rejects_non_finite_values(tmp_path: Path) -> None:
    path = _recording(tmp_path)
    content = path.read_text(encoding="utf-8")
    path.write_text(content.replace("0.3\t100\t6", "nan\t100\t6"), encoding="utf-8")

    with pytest.raises(ValueError, match="non_finite_value"):
        batch.read_recording(path)


def test_windows_are_20_seconds_at_10_second_stride_and_rebase_stably(tmp_path: Path) -> None:
    source = batch.SourceRecording(
        "1_1_001_V1", "1_1_001", "V1", _recording(tmp_path), "source.csv"
    )
    windows = list(batch.iter_windows(batch.read_recording(source.path)))
    run = batch.ImportRun("00000000-0000-0000-0000-000000000001", 2_000_000_000_000)

    assert [(item.source_start_ms, item.source_end_ms) for item in windows] == [
        (1_700_000_000_000, 1_700_000_020_000),
        (1_700_000_010_000, 1_700_000_030_000),
        (1_700_000_020_000, 1_700_000_040_000),
    ]
    first = batch.window_payload(
        run, source, windows[0], 1_700_000_000_000, windows[-1].source_end_ms
    )
    second = batch.window_payload(
        run, source, windows[1], 1_700_000_000_000, windows[-1].source_end_ms
    )
    assert first["windowEndMs"] - first["windowStartMs"] == 20_000
    assert second["windowStartMs"] - first["windowStartMs"] == 10_000
    assert second["sessionStartedAtMs"] == first["sessionStartedAtMs"]
    assert second["deviceInfo"]["sourceWindowStartMs"] == 1_700_000_010_000
    assert first["samples"][0]["sensor"] == "PPG_GREEN"
    assert first["samples"][1]["sensor"] == "EDA"
    assert batch.deterministic_window_id(run.run_id, source, windows[0]) == batch.deterministic_window_id(run.run_id, source, windows[0])
    assert batch.deterministic_window_id(str(UUID(int=2)), source, windows[0]) != batch.deterministic_window_id(run.run_id, source, windows[0])


class FakeRepository:
    def __init__(self) -> None:
        self.users: dict[str, UserRecord] = {}
        self.consents: dict[UUID, ConsentRecord] = {}
        self.created: list[dict] = []
        self.audits: list[dict] = []

    async def user_by_email(self, email: str):
        return self.users.get(email)

    async def create_patient(self, **values):
        self.created.append(values)
        user = UserRecord(
            id=values["user_id"], email=values["email"], password_hash=values["password_hash"],
            role="patient", status="active", must_change_password=False,
            created_at=datetime.now(timezone.utc),
        )
        self.users[user.email] = user
        self.consents[user.id] = _consent(user.id)
        return user

    async def current_consent(self, user_id: UUID):
        return self.consents.get(user_id)

    async def audit(self, **values):
        self.audits.append(values)


def test_account_creation_and_reuse_preserve_consent_contract(tmp_path: Path) -> None:
    async def scenario() -> None:
        repo = FakeRepository()
        source = batch.discover_recordings(tmp_path)[0][0]
        first, consent, created = await batch.ensure_test_account(
            repo, _keyring(), source, PASSWORD,
            password_hasher=lambda value: "hashed" if value == PASSWORD else "bad",
            password_verifier=lambda value, encoded: value == PASSWORD and encoded == "hashed",
        )
        second, _, reused_created = await batch.ensure_test_account(
            repo, _keyring(), source, PASSWORD,
            password_verifier=lambda value, encoded: value == PASSWORD and encoded == "hashed",
        )

        assert created is True and reused_created is False and first == second
        assert first.email == "alcohol-test+1_1_001_v1@neurotruth.local"
        assert consent.biosignal and consent.ai_analysis and not consent.notification
        assert repo.created[0]["consent"].notification is False
        assert repo.created[0]["name_encrypted"] != b"Alcohol Test 1_1_001_V1"
        assert repo.audits[0]["metadata"]["subjectVisit"] == "1_1_001_V1"

    _recording(tmp_path)
    asyncio.run(scenario())


@pytest.mark.parametrize(
    "field", ["voice", "report_generation", "camera_rppg", "face_video_retention"]
)
def test_existing_account_rejects_every_disabled_consent_being_enabled(field: str, tmp_path: Path) -> None:
    async def scenario() -> None:
        repo = FakeRepository()
        source = batch.discover_recordings(tmp_path)[0][0]
        user = _user()
        repo.users[user.email] = user
        repo.consents[user.id] = replace(_consent(user.id), **{field: True})

        with pytest.raises(ValueError, match="existing_account_contract_mismatch"):
            await batch.ensure_test_account(
                repo, _keyring(), source, PASSWORD,
                password_verifier=lambda value, encoded: value == PASSWORD and encoded == "hashed",
            )

    _recording(tmp_path)
    asyncio.run(scenario())


class FakeRunStore:
    def __init__(self, *, completed: bool = False) -> None:
        self.run = batch.ImportRun("00000000-0000-0000-0000-000000000001", 2_000_000_000_000, completed)
        self.completed_summaries: list[dict] = []

    async def resolve(self, **_values):
        return self.run

    async def complete(self, _run, summary):
        self.completed_summaries.append(summary)


class FakeAuditConnection:
    def __init__(self, metadata: dict | None, completed: bool = False) -> None:
        self.metadata = metadata
        self.completed = completed

    async def execute(self, _statement, _parameters):
        row = {"metadata": self.metadata} if self.metadata else None

        class Result:
            def mappings(self):
                return self

            def one_or_none(self):
                return row

        return Result()

    async def scalar(self, _statement, _parameters):
        return self.completed


class FakeAuditEngine:
    def __init__(self, connection: FakeAuditConnection) -> None:
        self.connection = connection

    def connect(self):
        connection = self.connection

        class Context:
            async def __aenter__(self):
                return connection

            async def __aexit__(self, *_args):
                return False

        return Context()


def test_audit_run_store_resumes_stable_anchor_and_completed_state() -> None:
    async def scenario() -> None:
        repo = FakeRepository()
        repo.engine = FakeAuditEngine(FakeAuditConnection({
            "runId": "00000000-0000-0000-0000-000000000001",
            "anchorMs": 1_900_000_000_000,
        }, completed=True))
        run = await batch.AuditRunStore(repo).resolve(
            run_id=None, new_run=False, anchor_ms=2_000_000_000_000
        )

        assert run == batch.ImportRun(
            "00000000-0000-0000-0000-000000000001", 1_900_000_000_000, True
        )
        assert repo.audits == []

    asyncio.run(scenario())


class FakeSensorService:
    def __init__(self, failure: Exception | None = None) -> None:
        self.calls: list[dict] = []
        self.failure = failure

    async def ingest(self, **values):
        self.calls.append(values)
        if self.failure is not None:
            raise self.failure
        sequence = values["payload"]["sequence"]
        return {
            "class": sequence % 2, "confidence": 0.8 + sequence / 100,
            "cravingProbability": sequence / 10,
            "recordingId": f"recording-{sequence}", "predictionId": f"prediction-{sequence}",
            "alertId": None,
        }


def test_importer_persists_without_notifications_writes_reports_and_resumes(monkeypatch, tmp_path: Path) -> None:
    async def scenario() -> None:
        data_root, output_root = tmp_path / "data", tmp_path / "output"
        _recording(data_root)
        repo, service, store = FakeRepository(), FakeSensorService(), FakeRunStore()
        user, consent = _user(), _consent(_user().id)

        async def fake_account(*_args, **_kwargs):
            return user, consent, True

        monkeypatch.setattr(batch, "ensure_test_account", fake_account)
        importer = batch.AlcoholTestImporter(repo, _keyring(), service, store)
        first = await importer.run(
            data_root=data_root, output_root=output_root, password=PASSWORD,
            subject_visit="1_1_001_V1", limit_windows=2, run_id=None,
            new_run=False, anchor_ms=store.run.anchor_ms,
        )
        first_ids = [call["payload"]["clientWindowId"] for call in service.calls]
        second_service = FakeSensorService()
        second = await batch.AlcoholTestImporter(repo, _keyring(), second_service, store).run(
            data_root=data_root, output_root=output_root, password=PASSWORD,
            subject_visit="1_1_001_V1", limit_windows=2, run_id=None,
            new_run=False, anchor_ms=store.run.anchor_ms,
        )

        assert first["windowCount"] == 2 and first["lowCount"] == 1 and first["highCount"] == 1
        assert first["subjects"]["1_1_001_V1"]["windowCount"] == 2
        assert first["subjects"]["1_1_001_V1"]["meanCravingProbability"] == pytest.approx(0.15)
        assert first["subjects"]["1_1_001_V1"]["maxCravingProbability"] == 0.2
        assert all(call["ai_analysis_allowed"] is True for call in service.calls)
        assert all(call["notification_allowed"] is False for call in service.calls)
        assert all(call["payload"]["deviceInfo"]["importRunId"] == store.run.run_id for call in service.calls)
        assert first_ids == [call["payload"]["clientWindowId"] for call in second_service.calls]
        assert second["runId"] == first["runId"]
        report_dir = output_root / store.run.run_id
        assert (report_dir / "accounts.csv").is_file()
        assert len((report_dir / "predictions.csv").read_text(encoding="utf-8").splitlines()) == 3
        with (report_dir / "predictions.csv").open(encoding="utf-8", newline="") as handle:
            assert list(csv.DictReader(handle))[0]["confidence"] == "0.81"
        assert json.loads((report_dir / "summary.json").read_text(encoding="utf-8"))["windowCount"] == 2
        assert store.completed_summaries == []

        complete_service = FakeSensorService()
        complete_store = FakeRunStore(completed=True)
        result = await batch.AlcoholTestImporter(repo, _keyring(), complete_service, complete_store).run(
            data_root=data_root, output_root=output_root, password=PASSWORD,
            subject_visit=None, limit_windows=None, run_id=None, new_run=False,
            anchor_ms=complete_store.run.anchor_ms,
        )
        assert result["status"] == "already_complete"
        assert complete_service.calls == []

    asyncio.run(scenario())


def test_operational_ingestion_failure_propagates_without_completion(monkeypatch, tmp_path: Path) -> None:
    async def scenario() -> None:
        data_root = tmp_path / "data"
        _recording(data_root)
        store = FakeRunStore()

        async def fake_account(*_args, **_kwargs):
            user = _user()
            return user, _consent(user.id), False

        monkeypatch.setattr(batch, "ensure_test_account", fake_account)
        importer = batch.AlcoholTestImporter(
            FakeRepository(), _keyring(), FakeSensorService(RuntimeError("storage_failed")), store
        )
        with pytest.raises(RuntimeError, match="storage_failed"):
            await importer.run(
                data_root=data_root, output_root=tmp_path / "output", password=PASSWORD,
                subject_visit=None, limit_windows=None, run_id=None, new_run=False,
                anchor_ms=store.run.anchor_ms,
            )
        assert store.completed_summaries == []

    asyncio.run(scenario())


def test_partial_full_run_audits_completion_after_recoverable_empty_window(monkeypatch, tmp_path: Path) -> None:
    async def scenario() -> None:
        data_root = tmp_path / "data"
        _remove_second_window(_recording(data_root))
        store, service = FakeRunStore(), FakeSensorService()

        async def fake_account(*_args, **_kwargs):
            user = _user()
            return user, _consent(user.id), False

        monkeypatch.setattr(batch, "ensure_test_account", fake_account)
        summary = await batch.AlcoholTestImporter(
            FakeRepository(), _keyring(), service, store
        ).run(
            data_root=data_root, output_root=tmp_path / "output", password=PASSWORD,
            subject_visit=None, limit_windows=None, run_id=None, new_run=False,
            anchor_ms=store.run.anchor_ms,
        )

        assert summary["status"] == "partial"
        assert summary["failures"] == ["1_1_001_V1:empty_window:2"]
        assert summary["windowCount"] == 2
        assert store.completed_summaries == [summary]

    asyncio.run(scenario())


def test_empty_reports_keep_stable_headers(tmp_path: Path) -> None:
    batch.AlcoholTestImporter._write_reports(tmp_path, [], [], {"status": "ok"})

    assert (tmp_path / "accounts.csv").read_text(encoding="utf-8").strip() == "subjectVisit,email,patientId,created"
    prediction_header = (tmp_path / "predictions.csv").read_text(encoding="utf-8").strip()
    assert prediction_header == (
        "runId,subjectVisit,sequence,clientWindowId,sourceWindowStartMs,sourceWindowEndMs,"
        "windowStartMs,windowEndMs,class,confidence,cravingProbability,recordingId,predictionId"
    )


def test_requested_cli_flags_and_validation_are_external_service_free(tmp_path: Path) -> None:
    _recording(tmp_path)
    args = batch._parser().parse_args([
        "--validate-only", "--subject-visit", "1_1_001_V1",
        "--limit-windows", "3", "--run-id", str(UUID(int=1)), "--new-run",
    ])
    report = batch.validate_dataset(tmp_path, args.subject_visit)

    assert args.validate_only and args.limit_windows == 3 and args.new_run
    assert report == {
        "status": "ok", "sourceCount": 1, "windowCount": 3,
        "failureCount": 0, "failures": [],
    }
