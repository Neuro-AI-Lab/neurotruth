-- ============================================================================
-- NeuroTruth 데모용 최종 스키마 v2.5 (대화/음성 통합)
-- PostgreSQL 16+
-- 작성 기준: 2026-07-14
--
-- 핵심 범위
--   1) 웨어러블 생체신호 수집
--   2) 갈망 유무 이진 분류(현재 0/1, 향후 다중분류·회귀 확장 가능)
--   3) Rule-based 알림 및 상태 확인 세션
--   4) 음성/텍스트 대화와 AUQ 평가
--   5) 대화에서 보고서용 핵심정보(Slot) 추출
--   6) 중재 기록, 종단 Memory, 세션 보고서 생성
--
-- 제외 범위
--   병원/기관, 의료진 계정, 보고서 실제 전송, EMR/FHIR, RAG
--
-- 기존 v2.3에서 제거한 과설계 테이블
--   devices, signal_windows, signal_window_sources, session_prediction_links,
--   assessment_instrument_versions, slot_definitions, session_slot_sources,
--   safety_events, agent_runs
--
-- v2.5 변경
--   audio_recordings와 stt_transcriptions를 제거하고 사용자 원본 음성 보존 정보는
--   message_audio_artifacts로 통합. 채팅/STT/AI 답변의 기준 텍스트는 messages에 저장
-- ============================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
SET search_path TO public;

-- updated_at 컬럼을 자동 갱신하기 위한 공통 함수
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

-- ============================================================================
-- 1. 사용자 계정 및 환자 기본정보
-- NeuroSync의 users, patient_profiles를 NeuroTruth 목적에 맞게 간소화
-- ============================================================================

CREATE TABLE public.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    password_hash text NOT NULL,
    role varchar(16) NOT NULL DEFAULT 'patient',
    status varchar(24) NOT NULL DEFAULT 'active',
    must_change_password boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT ck_users_role
        CHECK (role IN ('patient', 'admin')),
    CONSTRAINT ck_users_status
        CHECK (status IN ('active', 'disabled', 'pending_deletion'))
);

-- 탈퇴한 계정은 보존하면서, 활성 계정끼리는 이메일이 중복되지 않게 함
CREATE UNIQUE INDEX uq_users_active_email
    ON public.users (lower(email))
    WHERE deleted_at IS NULL;

CREATE TABLE public.patient_profiles (
    -- users.id와 1:1로 연결되는 환자 프로필 식별자
    user_id uuid PRIMARY KEY
        REFERENCES public.users(id) ON DELETE CASCADE,

    -- 실명은 평문 대신 애플리케이션에서 암호화한 bytea로 저장
    name_encrypted bytea,

    -- 데모에서 필요한 최소 인구통계 정보
    birth_year smallint,
    gender varchar(24),

    -- 연구/분석 시 실제 계정 ID 대신 사용할 가명 식별자
    pseudonymous_id text UNIQUE,
    pseudonymized_at timestamptz,

    -- 암호화 키 교체 시 어떤 키 버전을 사용했는지 기록
    encryption_key_version varchar(32) NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_patient_profiles_birth_year
        CHECK (birth_year IS NULL OR birth_year BETWEEN 1900 AND 2100),
    CONSTRAINT ck_patient_profiles_gender
        CHECK (gender IS NULL OR gender IN (
            'male', 'female', 'other', 'unknown', 'prefer_not_to_say'
        ))
);

-- ============================================================================
-- 2. 개인정보 및 데이터 처리 동의
-- NeuroSync의 consent_snapshots를 생체신호/AI/보고서 생성 중심으로 수정
-- ============================================================================

CREATE TABLE public.consent_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL
        REFERENCES public.users(id) ON DELETE RESTRICT,

    -- 기본 약관/개인정보/민감정보 동의
    tos boolean NOT NULL,
    privacy boolean NOT NULL,
    sensitive boolean NOT NULL,

    -- NeuroTruth 기능별 선택 동의
    biosignal boolean NOT NULL DEFAULT false,
    voice boolean NOT NULL DEFAULT false,
    ai_analysis boolean NOT NULL DEFAULT false,
    notification boolean NOT NULL DEFAULT false,
    report_generation boolean NOT NULL DEFAULT false,

    -- 사용자가 동의한 문서 버전
    tos_version varchar(32) NOT NULL,
    privacy_version varchar(32) NOT NULL,
    consent_form_version varchar(32) NOT NULL,

    collected_at timestamptz NOT NULL DEFAULT now(),
    collected_ip inet,
    user_agent text,

    -- 복합 FK에서 동일 사용자 동의인지 검증할 때 사용
    CONSTRAINT uq_consent_snapshots_id_user UNIQUE (id, user_id)
);

-- ============================================================================
-- 3. 모델/에이전트 버전 관리
-- 갈망 모델, STT, 대화/Slot/보고서/Memory 모델을 하나의 테이블에서 관리
-- ============================================================================

CREATE TABLE public.model_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- 어떤 구성요소의 버전인지 구분
    component varchar(32) NOT NULL,
    model_name text NOT NULL,
    model_version varchar(64) NOT NULL,

    -- 생체신호 특징, 프롬프트, 임계값 버전이 있는 경우 기록
    feature_version varchar(64),
    prompt_version varchar(64),
    threshold_version varchar(64),

    -- 현재 갈망 모델은 binary_classification 사용
    -- 향후 multiclass_classification/regression/hybrid로 확장 가능
    inference_task varchar(32),

    -- 클래스 매핑 또는 출력 형식 정의
    -- 예: {"classes":[{"index":0,"code":"no_craving"},{"index":1,"code":"craving"}]}
    output_schema jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- 윈도우 길이, stride, 전처리, 임계값 등 실행 설정
    -- 현재 10초라면 config에 {"window_sec":10,"stride_sec":10} 저장
    config jsonb NOT NULL DEFAULT '{}'::jsonb,

    artifact_uri text,
    git_commit varchar(64),
    is_active boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_model_versions_component
        CHECK (component IN (
            'craving_model', 'stt', 'dialogue_agent',
            'slot_agent', 'report_agent', 'memory_agent'
        )),
    CONSTRAINT ck_model_versions_inference_task
        CHECK (inference_task IS NULL OR inference_task IN (
            'binary_classification', 'multiclass_classification',
            'regression', 'hybrid', 'generative'
        )),
    CONSTRAINT ck_model_versions_output_schema
        CHECK (jsonb_typeof(output_schema) = 'object'),
    CONSTRAINT ck_model_versions_config
        CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT uq_model_versions_identity
        UNIQUE (component, model_name, model_version)
);

-- 데모에서는 구성요소별 활성 버전을 하나로 제한
CREATE UNIQUE INDEX uq_model_versions_active_component
    ON public.model_versions (component)
    WHERE is_active;

-- ============================================================================
-- 4. 생체신호 원본 기록과 갈망 예측
-- v2.3의 devices/signal_windows/link 테이블을 없애고 두 테이블로 간소화
-- ============================================================================

CREATE TABLE public.sensor_recordings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id uuid NOT NULL
        REFERENCES public.patient_profiles(user_id) ON DELETE RESTRICT,
    client_window_id uuid NOT NULL,

    -- canonical JSON을 gzip 후 AES-256-GCM envelope로 암호화한 파일의
    -- backend 전용 volume 기준 상대 경로만 기록
    storage_uri text NOT NULL,
    file_format varchar(16) NOT NULL,

    -- 하나의 파일/수집 구간에 PPG, EDA, ACC 등이 함께 포함될 수 있음
    modalities text[] NOT NULL,

    -- 별도 devices 테이블 대신 장치 정보를 JSON으로 간단히 보관
    -- 예: {"manufacturer":"Samsung","model":"Galaxy Watch8","sdk_version":"..."}
    device_info jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- modality별 sampling rate를 저장
    -- 예: {"ppg":25,"eda":4,"acc":25}
    sample_rates jsonb NOT NULL DEFAULT '{}'::jsonb,

    started_at timestamptz NOT NULL,
    ended_at timestamptz NOT NULL,
    bytes bigint,
    checksum_sha256 varchar(64),
    encryption_key_version varchar(32) NOT NULL,
    encryption_nonce bytea NOT NULL,

    -- 생체신호 수집 당시 유효했던 동의 스냅샷
    consent_snapshot_id uuid NOT NULL,

    -- 보존기간 만료 및 실제 삭제 시각
    delete_after timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_sensor_recordings_consent_patient
        FOREIGN KEY (consent_snapshot_id, patient_id)
        REFERENCES public.consent_snapshots(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_sensor_recordings_format
        CHECK (file_format IN ('parquet', 'csv', 'json', 'binary')),
    CONSTRAINT ck_sensor_recordings_modalities_nonempty
        CHECK (cardinality(modalities) > 0),
    CONSTRAINT ck_sensor_recordings_modalities_allowed
        CHECK (modalities <@ ARRAY[
            'ppg','eda','acc','hr','ibi','skin_temp','location_context'
        ]::text[]),
    CONSTRAINT ck_sensor_recordings_device_info
        CHECK (jsonb_typeof(device_info) = 'object'),
    CONSTRAINT ck_sensor_recordings_sample_rates
        CHECK (jsonb_typeof(sample_rates) = 'object'),
    CONSTRAINT ck_sensor_recordings_time
        CHECK (ended_at > started_at),
    CONSTRAINT ck_sensor_recordings_bytes
        CHECK (bytes IS NULL OR bytes >= 0),
    CONSTRAINT ck_sensor_recordings_encryption_nonce
        CHECK (octet_length(encryption_nonce) = 12),
    CONSTRAINT uq_sensor_recordings_patient_client_window
        UNIQUE (patient_id, client_window_id),
    CONSTRAINT uq_sensor_recordings_id_patient
        UNIQUE (id, patient_id)
);

CREATE TABLE public.craving_predictions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id uuid NOT NULL
        REFERENCES public.patient_profiles(user_id) ON DELETE RESTRICT,

    -- 원본 신호를 저장하지 않은 실시간 추론도 허용하기 위해 NULL 가능
    sensor_recording_id uuid,

    -- 모델이 실제로 처리한 구간. 현재 구현이 10초라면 두 시각 차이가 약 10초
    window_started_at timestamptz NOT NULL,
    window_ended_at timestamptz NOT NULL,
    input_modalities text[] NOT NULL,

    model_version_id uuid NOT NULL
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,

    -- 현재 이진 분류 결과
    -- 0/1의 의미는 model_versions.output_schema에서 관리
    predicted_class_index smallint,
    predicted_class_code varchar(32),
    predicted_class_probability numeric(6,5),
    class_probabilities jsonb,

    -- 향후 회귀/하이브리드 모델로 확장할 때 사용. 현재는 NULL
    continuous_value numeric(10,4),
    continuous_scale_min numeric(10,4),
    continuous_scale_max numeric(10,4),

    uncertainty numeric(6,5),

    -- 모델 입력 품질 및 움직임 정보
    signal_quality jsonb NOT NULL DEFAULT '{}'::jsonb,
    motion_context jsonb NOT NULL DEFAULT '{}'::jsonb,
    quality_gate_passed boolean NOT NULL DEFAULT true,

    -- 모델별 추가 출력이나 디버깅 정보를 유연하게 저장
    output_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    predicted_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_craving_predictions_recording_patient
        FOREIGN KEY (sensor_recording_id, patient_id)
        REFERENCES public.sensor_recordings(id, patient_id) ON DELETE SET NULL (sensor_recording_id),
    CONSTRAINT ck_craving_predictions_window
        CHECK (window_ended_at > window_started_at),
    CONSTRAINT ck_craving_predictions_modalities_nonempty
        CHECK (cardinality(input_modalities) > 0),
    CONSTRAINT ck_craving_predictions_class_index
        CHECK (predicted_class_index IS NULL OR predicted_class_index >= 0),
    CONSTRAINT ck_craving_predictions_class_pair
        CHECK ((predicted_class_index IS NULL) = (predicted_class_code IS NULL)),
    CONSTRAINT ck_craving_predictions_probability
        CHECK (predicted_class_probability IS NULL OR predicted_class_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_craving_predictions_class_probabilities
        CHECK (class_probabilities IS NULL OR jsonb_typeof(class_probabilities) = 'object'),
    CONSTRAINT ck_craving_predictions_uncertainty
        CHECK (uncertainty IS NULL OR uncertainty BETWEEN 0 AND 1),
    CONSTRAINT ck_craving_predictions_continuous_scale
        CHECK (
            (continuous_scale_min IS NULL AND continuous_scale_max IS NULL) OR
            (continuous_scale_min IS NOT NULL AND continuous_scale_max IS NOT NULL
             AND continuous_scale_max > continuous_scale_min)
        ),
    CONSTRAINT ck_craving_predictions_has_output
        CHECK (predicted_class_code IS NOT NULL OR continuous_value IS NOT NULL),
    CONSTRAINT uq_craving_predictions_window_model
        UNIQUE (patient_id, window_started_at, window_ended_at, model_version_id),
    CONSTRAINT uq_craving_predictions_id_patient
        UNIQUE (id, patient_id)
);

-- ============================================================================
-- 5. Rule-based 갈망 알림과 대화/중재 세션
-- NeuroSync의 risk_events를 갈망 알림 목적에 맞게 대체
-- ============================================================================

CREATE TABLE public.craving_alerts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id uuid NOT NULL,
    trigger_prediction_id uuid NOT NULL,

    -- 어떤 규칙으로 알림이 발생했는지 기록
    rule_code varchar(64) NOT NULL,
    rule_version varchar(32) NOT NULL,
    trigger_reason jsonb NOT NULL,

    status varchar(24) NOT NULL DEFAULT 'triggered',
    notification_channel varchar(16) NOT NULL DEFAULT 'in_app',
    triggered_at timestamptz NOT NULL DEFAULT now(),
    notified_at timestamptz,
    acknowledged_at timestamptz,
    dismissed_at timestamptz,
    expires_at timestamptz,

    CONSTRAINT fk_craving_alerts_prediction_patient
        FOREIGN KEY (trigger_prediction_id, patient_id)
        REFERENCES public.craving_predictions(id, patient_id) ON DELETE CASCADE,
    CONSTRAINT ck_craving_alerts_status
        CHECK (status IN (
            'triggered', 'notified', 'acknowledged',
            'session_started', 'dismissed', 'expired'
        )),
    CONSTRAINT ck_craving_alerts_channel
        CHECK (notification_channel IN ('in_app', 'push', 'demo')),
    CONSTRAINT ck_craving_alerts_reason
        CHECK (jsonb_typeof(trigger_reason) = 'object'),
    CONSTRAINT ck_craving_alerts_time
        CHECK (expires_at IS NULL OR expires_at >= triggered_at),
    CONSTRAINT uq_craving_alerts_id_patient
        UNIQUE (id, patient_id)
);

CREATE TABLE public.sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id uuid NOT NULL
        REFERENCES public.patient_profiles(user_id) ON DELETE RESTRICT,

    -- 알림으로 시작하지 않은 수동/예약 세션은 NULL
    trigger_alert_id uuid,

    session_type varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'created',
    started_at timestamptz,
    ended_at timestamptz,
    completion_reason varchar(32),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_sessions_alert_patient
        FOREIGN KEY (trigger_alert_id, patient_id)
        REFERENCES public.craving_alerts(id, patient_id) ON DELETE SET NULL (trigger_alert_id),
    CONSTRAINT ck_sessions_type
        CHECK (session_type IN ('alert_checkin', 'manual_checkin', 'scheduled_checkin')),
    CONSTRAINT ck_sessions_status
        CHECK (status IN (
            'created', 'in_progress', 'completed',
            'report_ready', 'closed', 'abandoned'
        )),
    CONSTRAINT ck_sessions_completion_reason
        CHECK (completion_reason IS NULL OR completion_reason IN (
            'normal', 'user_exit', 'timeout', 'technical_error', 'safety_exit'
        )),

    -- 상태별 시각 규칙
    -- created: 알림으로 행만 생성되고 아직 사용자가 응답하지 않은 상태
    -- in_progress: 사용자가 세션을 시작한 상태
    -- completed/report_ready/closed: 세션이 실제로 시작된 뒤 종료된 상태
    -- abandoned: 응답 전 타임아웃 또는 시작 후 이탈 모두 가능하므로 started_at은 NULL일 수 있음
    CONSTRAINT ck_sessions_state_time
        CHECK (
            (status = 'created'
                AND started_at IS NULL
                AND ended_at IS NULL)
            OR
            (status = 'in_progress'
                AND started_at IS NOT NULL
                AND ended_at IS NULL)
            OR
            (status IN ('completed', 'report_ready', 'closed')
                AND started_at IS NOT NULL
                AND ended_at IS NOT NULL)
            OR
            (status = 'abandoned'
                AND ended_at IS NOT NULL)
        ),

    -- started_at이 없는 사전 이탈(abandoned)도 허용하되,
    -- 실제로 시작된 세션은 종료 시각이 시작 시각보다 빠를 수 없음
    CONSTRAINT ck_sessions_time_order
        CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at),
    CONSTRAINT uq_sessions_id_patient
        UNIQUE (id, patient_id)
);

-- ============================================================================
-- 6. 대화 메시지와 사용자 원본 음성
-- 채팅과 음성 대화를 하나의 messages 타임라인으로 통합하고,
-- 동의하에 임시 보존하는 사용자 원본 음성만 message_audio_artifacts에 기록
-- ============================================================================

CREATE TABLE public.messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL
        REFERENCES public.sessions(id) ON DELETE CASCADE,

    -- 세션 안에서 대화 순서를 보장
    sequence_no integer NOT NULL,
    role varchar(12) NOT NULL,

    -- 직접 입력, 최종 STT 결과, AI 답변 모두 기준 텍스트를 암호화 저장
    content_encrypted bytea NOT NULL,
    encryption_key_version varchar(32) NOT NULL,

    -- 세션 전체가 아니라 메시지마다 text/voice/system 방식을 기록
    modality varchar(12) NOT NULL DEFAULT 'text',
    -- 음성에서 변환된 최종 텍스트를 사용자가 수정했는지 기록
    content_edited_by_user boolean NOT NULL DEFAULT false,

    -- v2.3의 agent_runs 테이블을 없애고 메시지에 생성 주체/버전만 기록
    source_agent varchar(24),
    model_version_id uuid
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,
    generation_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_messages_sequence
        CHECK (sequence_no > 0),
    CONSTRAINT ck_messages_role
        CHECK (role IN ('user', 'assistant', 'system')),
    CONSTRAINT ck_messages_modality
        CHECK (modality IN ('text', 'voice', 'system')),
    CONSTRAINT ck_messages_source_agent
        CHECK (source_agent IS NULL OR source_agent IN (
            'rule_engine', 'stt', 'normalizer',
            'dialogue', 'slot', 'report', 'system'
        )),
    CONSTRAINT ck_messages_generation_metadata
        CHECK (jsonb_typeof(generation_metadata) = 'object'),
    CONSTRAINT uq_messages_session_sequence
        UNIQUE (session_id, sequence_no),
    CONSTRAINT uq_messages_id_session
        UNIQUE (id, session_id)
);

CREATE TABLE public.message_audio_artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    session_id uuid NOT NULL,
    patient_id uuid NOT NULL,

    -- 향후 음성 기능 활성화 시 AES-256-GCM envelope로 암호화하여
    -- backend 전용 volume에 보존하고 volume 기준 상대 경로만 기록
    storage_uri text NOT NULL,
    encoding varchar(16) NOT NULL,
    sample_rate_hz integer NOT NULL,
    duration_ms integer NOT NULL,
    bytes bigint NOT NULL,

    consent_snapshot_id uuid NOT NULL,
    stt_model_version_id uuid
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,
    stt_confidence numeric(5,4),
    stt_latency_ms integer,
    processing_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- 보존된 사용자 원본 음성은 반드시 삭제 예정 시각을 가짐
    delete_after timestamptz NOT NULL,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_message_audio_artifacts_message_session
        FOREIGN KEY (message_id, session_id)
        REFERENCES public.messages(id, session_id) ON DELETE CASCADE,
    CONSTRAINT fk_message_audio_artifacts_session_patient
        FOREIGN KEY (session_id, patient_id)
        REFERENCES public.sessions(id, patient_id) ON DELETE CASCADE,
    CONSTRAINT fk_message_audio_artifacts_consent_patient
        FOREIGN KEY (consent_snapshot_id, patient_id)
        REFERENCES public.consent_snapshots(id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_message_audio_artifacts_encoding
        CHECK (encoding IN ('opus', 'pcm16', 'wav', 'm4a')),
    CONSTRAINT ck_message_audio_artifacts_sample_rate
        CHECK (sample_rate_hz > 0),
    CONSTRAINT ck_message_audio_artifacts_duration
        CHECK (duration_ms > 0),
    CONSTRAINT ck_message_audio_artifacts_bytes
        CHECK (bytes >= 0),
    CONSTRAINT ck_message_audio_artifacts_stt_confidence
        CHECK (stt_confidence IS NULL OR stt_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_message_audio_artifacts_stt_latency
        CHECK (stt_latency_ms IS NULL OR stt_latency_ms >= 0),
    CONSTRAINT ck_message_audio_artifacts_processing_metadata
        CHECK (jsonb_typeof(processing_metadata) = 'object'),
    CONSTRAINT ck_message_audio_artifacts_deleted_at
        CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    CONSTRAINT uq_message_audio_artifacts_message
        UNIQUE (message_id)
);

-- ============================================================================
-- 7. AUQ 등 주관적 갈망 평가
-- NeuroSync의 questionnaire_results를 갈망 평가 전용으로 수정
-- 채점 규칙은 애플리케이션/PRD에서 관리하고 DB는 결과와 버전을 보존
-- ============================================================================

CREATE TABLE public.craving_assessments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL
        REFERENCES public.sessions(id) ON DELETE CASCADE,

    instrument_code varchar(32) NOT NULL DEFAULT 'AUQ',
    instrument_version varchar(32) NOT NULL,
    phase varchar(24) NOT NULL,
    attempt_no smallint NOT NULL DEFAULT 1,

    -- 문항별 원응답
    answers_encrypted bytea NOT NULL,
    encryption_key_version varchar(32) NOT NULL,

    -- 실제 채점 결과와 가능한 범위
    raw_score numeric(8,2) NOT NULL,
    scale_min numeric(8,2) NOT NULL,
    scale_max numeric(8,2) NOT NULL,

    -- 채점 방식, 역채점 문항 등 재현에 필요한 정보
    scoring_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    completed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_craving_assessments_phase
        CHECK (phase IN ('pre_intervention', 'post_intervention', 'followup')),
    CONSTRAINT ck_craving_assessments_attempt
        CHECK (attempt_no > 0),
    CONSTRAINT ck_craving_assessments_scale
        CHECK (scale_max > scale_min AND raw_score BETWEEN scale_min AND scale_max),
    CONSTRAINT ck_craving_assessments_scoring_metadata
        CHECK (jsonb_typeof(scoring_metadata) = 'object'),
    CONSTRAINT uq_craving_assessments_attempt
        UNIQUE (session_id, instrument_code, instrument_version, phase, attempt_no),
    CONSTRAINT uq_craving_assessments_id_session
        UNIQUE (id, session_id)
);

-- ============================================================================
-- 8. 대화에서 추출한 보고서용 Slot
-- Slot은 갈망 점수를 저장하는 곳이 아니라, AUQ/모델 결과를 해석할 맥락을 저장
-- ============================================================================

CREATE TABLE public.session_slots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL
        REFERENCES public.sessions(id) ON DELETE CASCADE,

    -- 보고서 구성에 필요한 13개 상위 맥락 항목
    slot_key varchar(32) NOT NULL,

    -- Slot별 구조화 결과(JSON)
    -- 예: episode_trigger={"categories":["work_stress"],"detail":"회의에서 지적받음"}
    value_encrypted bytea NOT NULL,
    encryption_key_version varchar(32) NOT NULL,
    completion_status varchar(16) NOT NULL,

    confidence numeric(5,4),

    -- 근거가 된 메시지 ID를 배열로 저장
    -- 테이블 수를 줄이기 위해 별도 session_slot_sources 테이블은 사용하지 않음
    source_message_ids uuid[] NOT NULL DEFAULT '{}'::uuid[],

    extraction_model_version_id uuid
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,
    verification_status varchar(16) NOT NULL DEFAULT 'extracted',
    observed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_session_slots_key
        CHECK (slot_key IN (
            'episode_trigger',
            'current_context',
            'alcohol_context',
            'drinking_status',
            'habit_pattern',
            'emotional_context',
            'physical_context',
            'alcohol_expectancy',
            'coping_context',
            'support_context',
            'user_goal',
            'safety_context',
            'additional_context'
        )),
    CONSTRAINT ck_session_slots_completion_status
        CHECK (completion_status IN ('answered', 'unknown', 'declined')),
    CONSTRAINT ck_session_slots_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_session_slots_verification
        CHECK (verification_status IN ('extracted', 'confirmed', 'corrected', 'rejected')),
    CONSTRAINT uq_session_slots_session_key
        UNIQUE (session_id, slot_key)
);

-- Slot별 권장 값 예시
-- 1) episode_trigger
--    {"categories":["work_stress"],"detail":"회의 중 지적을 받은 뒤 갈망이 발생함"}
-- 2) current_context
--    {"location_type":"home","social_context":"alone","time_context":"after_work"}
-- 3) alcohol_context
--    {"alcohol_available":true,"cue_types":["alcohol_at_home"],"alcohol_type":"beer"}
-- 4) drinking_status
--    {"stage":"not_started"} 또는 {"stage":"already_drinking","reported_amount":"맥주 1캔"}
-- 5) habit_pattern
--    {"is_recurrent":true,"pattern_summary":"업무 스트레스 후 귀가하여 혼자 음주하는 경향"}
-- 6) emotional_context
--    {"emotions":["stress","frustration"],"detail":"업무 사건 이후 부정적 감정 증가"}
-- 7) physical_context
--    {"states":["fatigue"],"detail":"퇴근 후 피로가 심함"}
-- 8) alcohol_expectancy
--    {"expected_effects":["생각이 줄어듦","잠이 빨리 듦"]}
-- 9) coping_context
--    {"previously_effective":["walking","calling_family"],"current_barriers":["fatigue"]}
-- 10) support_context
--    {"available_support":["sibling"],"barriers":["상대방을 걱정시킬까 부담됨"]}
-- 11) user_goal
--    {"short_term_goal":"오늘 음주하지 않기","readiness":"ambivalent"}
-- 12) safety_context
--    {"concerns":["driving"],"requires_immediate_attention":true}
-- 13) additional_context
--    다른 Slot에 포함되지 않지만 보고서에 필요한 추가 사실

-- ============================================================================
-- 9. 중재, 종단 Memory, 세션 보고서
-- ============================================================================

CREATE TABLE public.interventions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL
        REFERENCES public.sessions(id) ON DELETE CASCADE,

    intervention_type varchar(32) NOT NULL,

    -- 왜 이 중재를 선택했는지 Slot/AUQ 근거 등을 JSON으로 기록
    selection_basis_encrypted bytea NOT NULL,

    -- 사용자에게 실제로 제시한 중재 내용
    content_encrypted bytea NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'recommended',

    -- 중재 전후 AUQ 결과 연결
    pre_assessment_id uuid,
    post_assessment_id uuid,

    helpfulness_0_10 numeric(4,2),
    user_feedback_encrypted bytea,
    encryption_key_version varchar(32) NOT NULL,
    model_version_id uuid
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,

    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_interventions_pre_assessment_session
        FOREIGN KEY (pre_assessment_id, session_id)
        REFERENCES public.craving_assessments(id, session_id) ON DELETE SET NULL (pre_assessment_id),
    CONSTRAINT fk_interventions_post_assessment_session
        FOREIGN KEY (post_assessment_id, session_id)
        REFERENCES public.craving_assessments(id, session_id) ON DELETE SET NULL (post_assessment_id),
    CONSTRAINT ck_interventions_type
        CHECK (intervention_type IN (
            'breathing', 'urge_surfing', 'attention_shift',
            'leave_location', 'refusal_practice', 'social_support',
            'grounding', 'hydration', 'self_monitoring', 'other'
        )),
    CONSTRAINT ck_interventions_status
        CHECK (status IN ('recommended', 'delivered', 'started', 'completed', 'skipped', 'failed')),
    CONSTRAINT ck_interventions_helpfulness
        CHECK (helpfulness_0_10 IS NULL OR helpfulness_0_10 BETWEEN 0 AND 10),
    CONSTRAINT ck_interventions_time
        CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE public.memory_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id uuid NOT NULL
        REFERENCES public.patient_profiles(user_id) ON DELETE RESTRICT,
    source_session_id uuid NOT NULL,

    -- 환자별 Memory 버전. 세션마다 새 스냅샷을 만들 수 있음
    version integer NOT NULL,

    -- 대화 요약 원문은 암호화하여 저장
    summary_encrypted bytea NOT NULL,
    encryption_key_version varchar(32) NOT NULL,

    -- 보고서/다음 대화에 활용할 종단 패턴
    trigger_patterns jsonb NOT NULL DEFAULT '[]'::jsonb,
    alcohol_use_patterns jsonb NOT NULL DEFAULT '[]'::jsonb,
    effective_coping jsonb NOT NULL DEFAULT '[]'::jsonb,
    support_patterns jsonb NOT NULL DEFAULT '[]'::jsonb,

    model_version_id uuid
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,
    is_current boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_memory_snapshots_session_patient
        FOREIGN KEY (source_session_id, patient_id)
        REFERENCES public.sessions(id, patient_id) ON DELETE RESTRICT,
    CONSTRAINT ck_memory_snapshots_version
        CHECK (version > 0),
    CONSTRAINT ck_memory_snapshots_trigger_patterns
        CHECK (jsonb_typeof(trigger_patterns) = 'array'),
    CONSTRAINT ck_memory_snapshots_alcohol_patterns
        CHECK (jsonb_typeof(alcohol_use_patterns) = 'array'),
    CONSTRAINT ck_memory_snapshots_effective_coping
        CHECK (jsonb_typeof(effective_coping) = 'array'),
    CONSTRAINT ck_memory_snapshots_support_patterns
        CHECK (jsonb_typeof(support_patterns) = 'array'),
    CONSTRAINT uq_memory_snapshots_version
        UNIQUE (patient_id, version)
);

CREATE UNIQUE INDEX uq_memory_snapshots_current
    ON public.memory_snapshots (patient_id)
    WHERE is_current;

CREATE TABLE public.session_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL
        REFERENCES public.sessions(id) ON DELETE CASCADE,

    -- 같은 세션의 보고서를 재생성해도 이전 버전을 남김
    version integer NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'generating',

    -- 모델/AUQ/Slot/중재를 종합한 구조화 보고서
    content_encrypted bytea,
    encryption_key_version varchar(32),

    -- 보고서 작성에 사용한 prediction/message/assessment/slot/intervention ID
    evidence_refs jsonb NOT NULL DEFAULT '{}'::jsonb,

    model_version_id uuid
        REFERENCES public.model_versions(id) ON DELETE RESTRICT,

    -- PDF/HTML 생성 시 파일 위치
    rendered_file_uri text,
    rendered_format varchar(8),
    failure_reason text,

    created_at timestamptz NOT NULL DEFAULT now(),
    generated_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_session_reports_version
        CHECK (version > 0),
    CONSTRAINT ck_session_reports_status
        CHECK (status IN ('generating', 'ready', 'failed')),
    CONSTRAINT ck_session_reports_evidence_refs
        CHECK (jsonb_typeof(evidence_refs) = 'object'),
    CONSTRAINT ck_session_reports_format
        CHECK (rendered_format IS NULL OR rendered_format IN ('pdf', 'html')),
    CONSTRAINT ck_session_reports_rendered_pair
        CHECK ((rendered_file_uri IS NULL) = (rendered_format IS NULL)),
    CONSTRAINT uq_session_reports_version
        UNIQUE (session_id, version)
);

-- ============================================================================
-- 10. 감사 로그
-- NeuroSync 구조를 거의 그대로 유지. 누가 어떤 데이터에 접근/변경했는지 기록
-- ============================================================================

CREATE TABLE public.auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    token_family_id uuid NOT NULL,
    refresh_token_hash char(64) NOT NULL,
    parent_session_id uuid REFERENCES public.auth_sessions(id) ON DELETE SET NULL,
    replaced_by_session_id uuid REFERENCES public.auth_sessions(id) ON DELETE SET NULL,
    device_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason varchar(32),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    CONSTRAINT uq_auth_sessions_refresh_hash UNIQUE (refresh_token_hash),
    CONSTRAINT ck_auth_sessions_hash CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_sessions_device CHECK (jsonb_typeof(device_metadata) = 'object'),
    CONSTRAINT ck_auth_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_sessions_revocation CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
    )
);

CREATE TABLE public.system_settings (
    id boolean PRIMARY KEY DEFAULT true CHECK (id),
    interventions_enabled boolean NOT NULL DEFAULT true,
    chat_timeout_seconds integer NOT NULL DEFAULT 3600,
    admin_signup_code_hash text NOT NULL,
    updated_by uuid REFERENCES public.users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_system_settings_chat_timeout
        CHECK (chat_timeout_seconds BETWEEN 60 AND 86400)
);

CREATE TABLE public.audit_logs (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    actor_id uuid
        REFERENCES public.users(id) ON DELETE SET NULL,
    actor_role varchar(16) NOT NULL,
    action text NOT NULL,
    resource_type text,
    resource_id uuid,
    ip inet,
    user_agent text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_audit_logs_actor_role
        CHECK (actor_role IN ('patient', 'admin', 'agent', 'system')),
    CONSTRAINT ck_audit_logs_metadata
        CHECK (jsonb_typeof(metadata) = 'object')
);

-- ============================================================================
-- 11. 조회 성능을 위한 인덱스
-- ============================================================================

CREATE INDEX idx_consent_snapshots_user_time
    ON public.consent_snapshots (user_id, collected_at DESC);

CREATE INDEX idx_sensor_recordings_patient_time
    ON public.sensor_recordings (patient_id, started_at DESC);

CREATE INDEX idx_sensor_recordings_delete_after
    ON public.sensor_recordings (delete_after)
    WHERE deleted_at IS NULL AND delete_after IS NOT NULL;

CREATE INDEX idx_craving_predictions_patient_time
    ON public.craving_predictions (patient_id, predicted_at DESC);

CREATE INDEX idx_craving_predictions_positive_label
    ON public.craving_predictions (patient_id, predicted_at DESC, predicted_class_code);

CREATE INDEX idx_craving_alerts_patient_time
    ON public.craving_alerts (patient_id, triggered_at DESC);

CREATE INDEX idx_sessions_patient_time
    ON public.sessions (patient_id, created_at DESC);

CREATE UNIQUE INDEX uq_sessions_one_active_per_patient
    ON public.sessions (patient_id)
    WHERE status IN ('created', 'in_progress');

CREATE INDEX idx_auth_sessions_user_expiry
    ON public.auth_sessions (user_id, expires_at DESC);

CREATE INDEX idx_auth_sessions_family
    ON public.auth_sessions (token_family_id);


-- 같은 알림에는 동시에 하나의 비-abandoned 세션만 허용한다.
-- 기존 세션이 응답 없음/사용자 이탈로 abandoned가 되면 동일 알림으로 재시도 세션 생성 가능.
CREATE UNIQUE INDEX uq_sessions_non_abandoned_trigger_alert
    ON public.sessions (trigger_alert_id)
    WHERE trigger_alert_id IS NOT NULL
      AND status <> 'abandoned';

CREATE INDEX idx_messages_session_sequence
    ON public.messages (session_id, sequence_no);

CREATE INDEX idx_message_audio_artifacts_session
    ON public.message_audio_artifacts (session_id, created_at);

CREATE INDEX idx_message_audio_artifacts_delete_after
    ON public.message_audio_artifacts (delete_after)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_craving_assessments_session_phase
    ON public.craving_assessments (session_id, phase, completed_at DESC);

CREATE INDEX idx_session_slots_session
    ON public.session_slots (session_id, slot_key);

CREATE INDEX idx_interventions_session
    ON public.interventions (session_id, created_at);

CREATE INDEX idx_memory_snapshots_patient
    ON public.memory_snapshots (patient_id, created_at DESC);

CREATE INDEX idx_session_reports_session
    ON public.session_reports (session_id, version DESC);

CREATE INDEX idx_audit_logs_actor_time
    ON public.audit_logs (actor_id, created_at DESC);

-- ============================================================================
-- 12. updated_at 자동 갱신 Trigger
-- ============================================================================

CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON public.users
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_patient_profiles_updated_at
BEFORE UPDATE ON public.patient_profiles
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_sessions_updated_at
BEFORE UPDATE ON public.sessions
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_session_slots_updated_at
BEFORE UPDATE ON public.session_slots
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_session_reports_updated_at
BEFORE UPDATE ON public.session_reports
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_system_settings_updated_at
BEFORE UPDATE ON public.system_settings
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ============================================================================
-- 13. DBeaver에서 확인할 수 있는 한글 설명(Comment)
-- ============================================================================

COMMENT ON TABLE public.users IS 'NeuroTruth 로그인 계정. 현재 데모 역할은 patient와 admin만 사용한다.';
COMMENT ON COLUMN public.users.id IS '계정 고유 UUID';
COMMENT ON COLUMN public.users.email IS '로그인 이메일';
COMMENT ON COLUMN public.users.password_hash IS '단방향 해시된 비밀번호';
COMMENT ON COLUMN public.users.role IS '계정 권한: patient 또는 admin';
COMMENT ON COLUMN public.users.status IS '계정 상태: active, disabled, pending_deletion';
COMMENT ON COLUMN public.users.must_change_password IS '관리자 발급 임시 비밀번호의 변경 필요 여부';
COMMENT ON COLUMN public.users.deleted_at IS '탈퇴/비활성화 시각. NULL이면 활성 계정';

COMMENT ON TABLE public.patient_profiles IS '환자의 최소 프로필과 가명처리 정보. users와 1:1 관계이다.';
COMMENT ON COLUMN public.patient_profiles.user_id IS 'users.id를 참조하는 환자 식별자';
COMMENT ON COLUMN public.patient_profiles.name_encrypted IS '암호화된 환자 이름';
COMMENT ON COLUMN public.patient_profiles.birth_year IS '출생연도';
COMMENT ON COLUMN public.patient_profiles.gender IS '성별 코드';
COMMENT ON COLUMN public.patient_profiles.pseudonymous_id IS '연구/분석용 가명 식별자';
COMMENT ON COLUMN public.patient_profiles.encryption_key_version IS '민감정보 암호화에 사용한 키 버전';

COMMENT ON TABLE public.consent_snapshots IS '사용자가 특정 시점에 동의한 약관과 데이터 처리 항목을 스냅샷으로 보존한다.';
COMMENT ON COLUMN public.consent_snapshots.biosignal IS 'PPG/EDA/ACC 등 생체신호 수집 동의';
COMMENT ON COLUMN public.consent_snapshots.voice IS '음성 녹음 및 STT 처리 동의';
COMMENT ON COLUMN public.consent_snapshots.ai_analysis IS 'AI 기반 분석 및 대화 처리 동의';
COMMENT ON COLUMN public.consent_snapshots.notification IS '갈망 감지 알림 수신 동의';
COMMENT ON COLUMN public.consent_snapshots.report_generation IS '세션 보고서 생성 동의';

COMMENT ON TABLE public.model_versions IS '갈망 모델과 STT/LLM 에이전트의 버전, 설정, 출력 계약을 관리한다.';
COMMENT ON COLUMN public.model_versions.component IS '모델 또는 에이전트 종류';
COMMENT ON COLUMN public.model_versions.inference_task IS 'binary/multiclass/regression/hybrid/generative';
COMMENT ON COLUMN public.model_versions.output_schema IS '클래스 인덱스와 코드 등 출력 형식 정의';
COMMENT ON COLUMN public.model_versions.config IS '윈도우 길이, stride, 전처리, 임계값 등 실행 설정';

COMMENT ON TABLE public.sensor_recordings IS '웨어러블에서 수집한 원본 생체신호 파일의 위치와 메타데이터를 저장한다.';
COMMENT ON COLUMN public.sensor_recordings.storage_uri IS 'canonical JSON을 gzip 후 AES-256-GCM으로 암호화해 backend 전용 volume에 저장한 파일의 상대 경로';
COMMENT ON COLUMN public.sensor_recordings.modalities IS '해당 수집 구간에 포함된 신호 종류 배열';
COMMENT ON COLUMN public.sensor_recordings.device_info IS '제조사, 모델, SDK 버전 등 장치 정보 JSON';
COMMENT ON COLUMN public.sensor_recordings.sample_rates IS '신호별 sampling rate JSON';

COMMENT ON TABLE public.craving_predictions IS '생체신호 시간 구간별 갈망 모델 추론 결과. 현재는 갈망 유무 이진 분류를 사용한다.';
COMMENT ON COLUMN public.craving_predictions.window_started_at IS '모델 입력 구간 시작 시각';
COMMENT ON COLUMN public.craving_predictions.window_ended_at IS '모델 입력 구간 종료 시각';
COMMENT ON COLUMN public.craving_predictions.predicted_class_index IS '예측 클래스 숫자 인덱스. 0/1 의미는 model_versions.output_schema에서 정의';
COMMENT ON COLUMN public.craving_predictions.predicted_class_code IS '예측 클래스 코드. 예: no_craving, craving';
COMMENT ON COLUMN public.craving_predictions.predicted_class_probability IS '최종 예측 클래스의 확률';
COMMENT ON COLUMN public.craving_predictions.class_probabilities IS '전체 클래스별 확률 JSON';
COMMENT ON COLUMN public.craving_predictions.continuous_value IS '향후 회귀/하이브리드 모델 확장용 연속값';
COMMENT ON COLUMN public.craving_predictions.uncertainty IS '모델 불확실성 값(0~1)';
COMMENT ON COLUMN public.craving_predictions.signal_quality IS 'PPG/EDA 등 입력 신호 품질 정보';
COMMENT ON COLUMN public.craving_predictions.motion_context IS '움직임 수준 및 motion artifact 관련 정보';
COMMENT ON COLUMN public.craving_predictions.quality_gate_passed IS '신호 품질 기준 통과 여부';

COMMENT ON TABLE public.craving_alerts IS '갈망 예측에 Rule을 적용하여 발생한 사용자 알림 기록.';
COMMENT ON COLUMN public.craving_alerts.trigger_prediction_id IS '알림을 발생시킨 갈망 예측 결과';
COMMENT ON COLUMN public.craving_alerts.rule_code IS '적용된 알림 규칙 코드';
COMMENT ON COLUMN public.craving_alerts.trigger_reason IS '알림 판단 근거 JSON';

COMMENT ON TABLE public.sessions IS '갈망 알림 이후 또는 사용자가 직접 시작한 상태 확인·대화·중재 단위.';
COMMENT ON COLUMN public.sessions.trigger_alert_id IS '세션을 시작시킨 갈망 알림. 수동 세션은 NULL이며, abandoned 세션 이후 동일 알림으로 재시도할 수 있다.';
COMMENT ON COLUMN public.sessions.session_type IS 'alert_checkin, manual_checkin, scheduled_checkin';
COMMENT ON COLUMN public.sessions.status IS 'created, in_progress, completed, report_ready, closed, abandoned. abandoned는 시작 전 응답 없음과 시작 후 이탈을 모두 포함한다.';
COMMENT ON COLUMN public.sessions.started_at IS '사용자가 실제로 세션을 시작한 시각. 시작 전 abandoned이면 NULL 가능';
COMMENT ON COLUMN public.sessions.ended_at IS '세션 종료 또는 abandoned 처리 시각';
COMMENT ON COLUMN public.sessions.completion_reason IS '정상 종료, 사용자 이탈, 응답 없음 타임아웃, 오류 등의 종료 사유';

COMMENT ON TABLE public.messages IS '텍스트 입력, 사용자 음성의 최종 STT 문장, AI 답변을 하나의 순서로 보존하는 기준 대화 타임라인. AI 답변을 TTS로 재생해도 음성 산출물은 저장하지 않는다.';
COMMENT ON COLUMN public.messages.sequence_no IS '세션 안에서의 메시지 순서';
COMMENT ON COLUMN public.messages.role IS 'user, assistant, system';
COMMENT ON COLUMN public.messages.content_encrypted IS '직접 입력, 최종 STT 결과 또는 AI 답변의 암호화된 기준 텍스트';
COMMENT ON COLUMN public.messages.modality IS '메시지별 전달 방식: text, voice, system. 한 세션에서 text와 voice를 섞을 수 있다.';
COMMENT ON COLUMN public.messages.content_edited_by_user IS '사용자가 STT로 생성된 최종 메시지 텍스트를 수정했는지 여부';
COMMENT ON COLUMN public.messages.source_agent IS '메시지를 생성한 구성요소 또는 에이전트';
COMMENT ON COLUMN public.messages.generation_metadata IS 'LLM token, latency, 요청 ID 등 선택적 생성 메타데이터';

COMMENT ON TABLE public.message_audio_artifacts IS '향후 음성 기능 활성화 시 동의하에 backend 전용 암호화 volume에 임시 보존하는 사용자 원본 음성과 STT 처리 메타데이터. 현재 구현에서는 음성 수집이 비활성화되며 AI TTS 산출물은 저장하지 않는다.';
COMMENT ON COLUMN public.message_audio_artifacts.message_id IS '최종 STT 텍스트를 기준 본문으로 보존한 messages.id';
COMMENT ON COLUMN public.message_audio_artifacts.storage_uri IS 'AES-256-GCM envelope로 암호화해 backend 전용 volume에 저장한 사용자 원본 음성 파일의 상대 경로';
COMMENT ON COLUMN public.message_audio_artifacts.consent_snapshot_id IS '원본 음성 보존 당시 동일 환자의 동의 스냅샷';
COMMENT ON COLUMN public.message_audio_artifacts.stt_model_version_id IS '최종 STT 처리에 사용한 선택적 모델 버전';
COMMENT ON COLUMN public.message_audio_artifacts.processing_metadata IS '최종 STT 처리의 선택적 부가 메타데이터 JSON. 재시도 이력이나 후보 텍스트는 저장하지 않는다.';
COMMENT ON COLUMN public.message_audio_artifacts.delete_after IS '임시 보존한 사용자 원본 음성 파일의 필수 삭제 예정 시각';
COMMENT ON COLUMN public.message_audio_artifacts.deleted_at IS '외부 사용자 원본 음성 파일의 실제 삭제 시각';

COMMENT ON TABLE public.craving_assessments IS 'AUQ 등 주관적 갈망 평가 결과. 생체신호 모델 예측 및 Slot과 분리해서 저장한다.';
COMMENT ON COLUMN public.craving_assessments.phase IS '중재 전, 중재 후, 후속 평가 구분';
COMMENT ON COLUMN public.craving_assessments.answers_encrypted IS 'AES-256-GCM envelope로 암호화한 문항별 원응답 JSON';
COMMENT ON COLUMN public.craving_assessments.raw_score IS '평가도구 채점 결과';
COMMENT ON COLUMN public.craving_assessments.scoring_metadata IS '채점 방식, 역채점 문항 등 재현 정보';

COMMENT ON TABLE public.session_slots IS '대화에서 추출한 갈망 발생 맥락, 음주 패턴, 대처 자원 등 보고서용 핵심정보.';
COMMENT ON COLUMN public.session_slots.slot_key IS '13개 보고서용 상위 Slot 중 하나';
COMMENT ON COLUMN public.session_slots.value_encrypted IS 'AES-256-GCM envelope로 암호화한 Slot별 구조화 JSON 값';
COMMENT ON COLUMN public.session_slots.source_message_ids IS '해당 Slot의 근거가 된 대화 메시지 UUID 배열';
COMMENT ON COLUMN public.session_slots.verification_status IS '추출/사용자 확인/수정/거절 상태';

COMMENT ON TABLE public.interventions IS '사용자에게 제안하거나 수행한 중재와 중재 전후 AUQ, 피드백을 기록한다.';
COMMENT ON COLUMN public.interventions.selection_basis_encrypted IS '암호화된 중재 선택 Slot/AUQ 근거';
COMMENT ON COLUMN public.interventions.content_encrypted IS '암호화된 사용자 제시 중재 내용';
COMMENT ON COLUMN public.interventions.helpfulness_0_10 IS '사용자가 평가한 중재 도움 정도';

COMMENT ON TABLE public.memory_snapshots IS '여러 세션에서 확인된 갈망 유발 패턴과 효과적 대처를 환자별 종단 Memory로 보존한다.';
COMMENT ON COLUMN public.memory_snapshots.summary_encrypted IS '암호화된 종단 요약 문장';
COMMENT ON COLUMN public.memory_snapshots.trigger_patterns IS '반복되는 갈망 촉발 요인 배열';
COMMENT ON COLUMN public.memory_snapshots.alcohol_use_patterns IS '반복되는 음주 상황/습관 배열';
COMMENT ON COLUMN public.memory_snapshots.effective_coping IS '효과가 확인된 대처 방법 배열';
COMMENT ON COLUMN public.memory_snapshots.support_patterns IS '사용 가능한 사회적 지원 및 장애 요인 배열';
COMMENT ON COLUMN public.memory_snapshots.is_current IS '현재 보고서/대화에서 사용할 최신 Memory 여부';

COMMENT ON TABLE public.session_reports IS '한 세션의 생체신호 예측, AUQ, 대화 Slot, 중재 결과를 종합한 구조화 보고서.';
COMMENT ON COLUMN public.session_reports.version IS '보고서 재생성 시 증가하는 버전 번호';
COMMENT ON COLUMN public.session_reports.content_encrypted IS 'AES-256-GCM envelope로 암호화한 최종 구조화 보고서 JSON';
COMMENT ON COLUMN public.session_reports.evidence_refs IS '보고서 근거로 사용한 데이터 ID 목록';
COMMENT ON COLUMN public.session_reports.rendered_file_uri IS '생성된 PDF/HTML 파일 위치';

COMMENT ON TABLE public.audit_logs IS '민감정보 접근, 예측 생성, 보고서 조회/생성 등 주요 시스템 행위의 감사 기록.';
COMMENT ON TABLE public.auth_sessions IS 'opaque refresh token 해시와 회전·폐기 계보를 보존한다.';
COMMENT ON TABLE public.system_settings IS '개입 활성화, 채팅 제한시간, 관리자 가입 코드 해시를 보존하는 단일 행 설정.';

COMMIT;

