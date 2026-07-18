"""Allow retained ten-second and new twenty-second rPPG captures."""

from alembic import op


revision = "20260717_0005"
down_revision = "20260716_0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        ALTER TABLE public.rppg_captures
          DROP CONSTRAINT ck_rppg_capture_duration;
        ALTER TABLE public.rppg_captures
          ADD CONSTRAINT ck_rppg_capture_duration CHECK (
            duration_ms BETWEEN 9500 AND 10500
            OR duration_ms BETWEEN 19500 AND 20500
          );
    """)


def downgrade() -> None:
    raise RuntimeError("Do not downgrade while retained 20-second rPPG captures may exist")
