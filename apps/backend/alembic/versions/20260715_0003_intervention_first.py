"""Add intervention-first dialogue state, evidence-linked inference, and ordering."""

from alembic import op


revision = "20260715_0003"
down_revision = "20260715_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE public.model_versions DROP CONSTRAINT ck_model_versions_component;
        ALTER TABLE public.model_versions ADD CONSTRAINT ck_model_versions_component
          CHECK (component IN (
            'craving_model','rppg_model','stt','dialogue_agent','slot_agent',
            'report_agent','memory_agent','state_inference_agent'
          ));

        ALTER TABLE public.sessions
          ADD COLUMN interaction_phase varchar(32),
          ADD COLUMN dialogue_state_encrypted bytea,
          ADD COLUMN dialogue_state_key_version varchar(32),
          ADD CONSTRAINT ck_sessions_interaction_phase
            CHECK (interaction_phase IS NULL OR interaction_phase IN (
              'safety_check','intervention_dialogue','completed','abandoned'
            )),
          ADD CONSTRAINT ck_sessions_dialogue_state_pair
            CHECK ((dialogue_state_encrypted IS NULL) = (dialogue_state_key_version IS NULL));

        ALTER TABLE public.interventions
          ADD COLUMN presentation_order smallint,
          ADD COLUMN evidence_refs jsonb,
          ADD CONSTRAINT ck_interventions_presentation_order
            CHECK (presentation_order IS NULL OR presentation_order > 0),
          ADD CONSTRAINT ck_interventions_evidence_refs
            CHECK (evidence_refs IS NULL OR jsonb_typeof(evidence_refs)='object');
        CREATE UNIQUE INDEX uq_interventions_session_presentation_order
          ON public.interventions(session_id,presentation_order)
          WHERE presentation_order IS NOT NULL;

        CREATE TABLE public.state_inferences (
          id uuid PRIMARY KEY,
          patient_id uuid NOT NULL REFERENCES public.patient_profiles(user_id) ON DELETE RESTRICT,
          session_id uuid,
          trigger_prediction_id uuid,
          inference_scope varchar(16) NOT NULL,
          state_class varchar(16) NOT NULL,
          confidence numeric(6,5),
          evidence_refs jsonb NOT NULL DEFAULT '{}'::jsonb,
          payload_encrypted bytea NOT NULL,
          summary_encrypted bytea,
          encryption_key_version varchar(32) NOT NULL,
          rule_version varchar(64) NOT NULL,
          summary_model_version_id uuid REFERENCES public.model_versions(id) ON DELETE RESTRICT,
          summary_status varchar(16) NOT NULL,
          created_at timestamptz NOT NULL DEFAULT now(),
          CONSTRAINT fk_state_inferences_session_patient
            FOREIGN KEY (session_id,patient_id)
            REFERENCES public.sessions(id,patient_id) ON DELETE SET NULL (session_id),
          CONSTRAINT fk_state_inferences_prediction_patient
            FOREIGN KEY (trigger_prediction_id,patient_id)
            REFERENCES public.craving_predictions(id,patient_id) ON DELETE SET NULL (trigger_prediction_id),
          CONSTRAINT ck_state_inferences_scope
            CHECK (inference_scope IN ('realtime','longitudinal')),
          CONSTRAINT ck_state_inferences_class
            CHECK (state_class IN ('low','mid','high','unknown')),
          CONSTRAINT ck_state_inferences_confidence
            CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
          CONSTRAINT ck_state_inferences_evidence_refs
            CHECK (jsonb_typeof(evidence_refs)='object'),
          CONSTRAINT ck_state_inferences_summary_status
            CHECK (summary_status IN ('pending','ready','unavailable'))
        );
        CREATE INDEX idx_state_inferences_patient_created
          ON public.state_inferences(patient_id,created_at DESC);
        CREATE INDEX idx_state_inferences_session_created
          ON public.state_inferences(session_id,created_at);
        CREATE INDEX idx_state_inferences_trigger_prediction
          ON public.state_inferences(trigger_prediction_id);
        CREATE UNIQUE INDEX uq_state_inferences_patient_scope_event
          ON public.state_inferences(patient_id,inference_scope,(evidence_refs->>'eventKey'))
          WHERE evidence_refs ? 'eventKey';
    """)


def downgrade() -> None:
    raise RuntimeError("Do not downgrade while intervention-first data may exist")
