"""Add consent-gated DGX rPPG captures and durable analysis jobs."""

from alembic import op


revision = "20260715_0002"
down_revision = "20260715_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE public.consent_snapshots
          ADD COLUMN camera_rppg boolean NOT NULL DEFAULT false,
          ADD COLUMN face_video_retention boolean NOT NULL DEFAULT false;

        ALTER TABLE public.model_versions DROP CONSTRAINT ck_model_versions_component;
        ALTER TABLE public.model_versions ADD CONSTRAINT ck_model_versions_component
          CHECK (component IN (
            'craving_model','rppg_model','stt','dialogue_agent',
            'slot_agent','report_agent','memory_agent'
          ));

        CREATE TABLE public.rppg_captures (
          id uuid PRIMARY KEY,
          patient_id uuid NOT NULL REFERENCES public.patient_profiles(user_id) ON DELETE RESTRICT,
          session_id uuid,
          client_capture_id uuid NOT NULL,
          consent_snapshot_id uuid NOT NULL,
          captured_at timestamptz NOT NULL,
          duration_ms integer NOT NULL,
          content_type varchar(64) NOT NULL,
          byte_size bigint NOT NULL,
          checksum_sha256 char(64) NOT NULL,
          storage_uri text NOT NULL,
          encryption_key_version varchar(32) NOT NULL,
          encryption_nonce bytea NOT NULL,
          capture_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
          deletion_state varchar(24) NOT NULL DEFAULT 'active',
          deletion_error_code varchar(64),
          created_at timestamptz NOT NULL DEFAULT now(),
          updated_at timestamptz NOT NULL DEFAULT now(),
          deleted_at timestamptz,
          CONSTRAINT fk_rppg_capture_session_patient FOREIGN KEY (session_id,patient_id)
            REFERENCES public.sessions(id,patient_id) ON DELETE SET NULL (session_id),
          CONSTRAINT fk_rppg_capture_consent_patient FOREIGN KEY (consent_snapshot_id,patient_id)
            REFERENCES public.consent_snapshots(id,user_id) ON DELETE RESTRICT,
          CONSTRAINT uq_rppg_capture_patient_client UNIQUE (patient_id,client_capture_id),
          CONSTRAINT uq_rppg_capture_id_patient UNIQUE (id,patient_id),
          CONSTRAINT ck_rppg_capture_duration CHECK (duration_ms BETWEEN 9500 AND 10500),
          CONSTRAINT ck_rppg_capture_size CHECK (byte_size > 0),
          CONSTRAINT ck_rppg_capture_hash CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
          CONSTRAINT ck_rppg_capture_nonce CHECK (octet_length(encryption_nonce)=12),
          CONSTRAINT ck_rppg_capture_delete_state CHECK (deletion_state IN ('active','deleting','delete_failed','deleted')),
          CONSTRAINT ck_rppg_capture_metadata CHECK (jsonb_typeof(capture_metadata)='object')
        );
        CREATE INDEX idx_rppg_captures_patient_time ON public.rppg_captures(patient_id,captured_at DESC);
        CREATE INDEX idx_rppg_captures_delete_state ON public.rppg_captures(deletion_state) WHERE deletion_state<>'deleted';

        CREATE TABLE public.rppg_analysis_jobs (
          id uuid PRIMARY KEY,
          capture_id uuid NOT NULL REFERENCES public.rppg_captures(id) ON DELETE CASCADE,
          attempt_no smallint NOT NULL,
          retry_of_job_id uuid REFERENCES public.rppg_analysis_jobs(id) ON DELETE RESTRICT,
          status varchar(24) NOT NULL DEFAULT 'queued',
          measurement_id text,
          model_version_id uuid REFERENCES public.model_versions(id) ON DELETE RESTRICT,
          heart_rate_bpm numeric(8,3),
          quality_score numeric(7,6),
          quality_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
          model_name text,
          checkpoint text,
          inference_device text,
          processing_ms integer,
          provider_response_encrypted bytea,
          waveform_encrypted bytea,
          encryption_key_version varchar(32),
          failure_code varchar(64),
          retry_allowed boolean NOT NULL DEFAULT false,
          started_at timestamptz,
          finished_at timestamptz,
          created_at timestamptz NOT NULL DEFAULT now(),
          updated_at timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT uq_rppg_job_capture_attempt UNIQUE (capture_id,attempt_no),
          CONSTRAINT ck_rppg_job_attempt CHECK (attempt_no > 0),
          CONSTRAINT ck_rppg_job_status CHECK (status IN ('queued','running','completed','retry_required','failed')),
          CONSTRAINT ck_rppg_job_quality CHECK (quality_score IS NULL OR quality_score BETWEEN 0 AND 1),
          CONSTRAINT ck_rppg_job_reasons CHECK (jsonb_typeof(quality_reasons)='array')
        );
        CREATE INDEX idx_rppg_jobs_status_created ON public.rppg_analysis_jobs(status,created_at);
        CREATE UNIQUE INDEX uq_rppg_jobs_one_active_capture
          ON public.rppg_analysis_jobs(capture_id) WHERE status IN ('queued','running');

        ALTER TABLE public.craving_predictions ADD COLUMN rppg_analysis_job_id uuid;
        ALTER TABLE public.craving_predictions ADD CONSTRAINT fk_craving_prediction_rppg_job
          FOREIGN KEY (rppg_analysis_job_id) REFERENCES public.rppg_analysis_jobs(id) ON DELETE RESTRICT;
        CREATE UNIQUE INDEX uq_craving_predictions_rppg_job
          ON public.craving_predictions(rppg_analysis_job_id) WHERE rppg_analysis_job_id IS NOT NULL;
    """)


def downgrade() -> None:
    raise RuntimeError("Do not downgrade while retained rPPG data may exist")
