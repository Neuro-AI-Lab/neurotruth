"""Allow free dialogue sessions and enforce client-message idempotency."""

from alembic import op


revision = "20260716_0004"
down_revision = "20260715_0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE public.sessions DROP CONSTRAINT ck_sessions_interaction_phase;
        ALTER TABLE public.sessions ADD CONSTRAINT ck_sessions_interaction_phase
          CHECK (interaction_phase IS NULL OR interaction_phase IN (
            'safety_check','intervention_dialogue','free_dialogue','completed','abandoned'
          ));

        CREATE UNIQUE INDEX uq_messages_session_client_message_id
          ON public.messages(session_id,(generation_metadata->>'clientMessageId'))
          WHERE role='user'
            AND generation_metadata ? 'clientMessageId'
            AND generation_metadata->>'clientMessageId' IS NOT NULL;
    """)


def downgrade() -> None:
    raise RuntimeError("Do not downgrade while free-dialogue data may exist")
