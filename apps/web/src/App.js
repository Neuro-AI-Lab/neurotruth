import { useCallback, useEffect, useMemo, useState } from "react";
import api, {
  clearSession,
  getStoredRefreshToken,
  setAccessToken,
  setAuthFailureHandler,
  storeRefreshToken,
} from "./api";

const initialAuth = { email: "", password: "", signupCode: "" };
const revealableResourceTypes = new Set([
  "patient_profile",
  "message",
  "assessment",
  "slot",
  "intervention",
  "memory",
  "report",
  "state_inference",
]);

const dashboardRanges = [
  { value: "24h", label: "24시간" },
  { value: "7d", label: "7일" },
  { value: "30d", label: "30일" },
];

const classLabels = { low: "Low", mid: "Mid", high: "High", unknown: "Unknown" };
const eventLabels = {
  detection: "갈망 탐지",
  notification: "알림",
  session_started: "대화 시작",
  intervention: "중재 제안",
  session_finished: "대화 종료",
};
const reportLabels = { not_started: "생성 전", generating: "생성 중", ready: "준비됨", failed: "실패" };
const summaryLabels = { pending: "생성 중", ready: "준비됨", unavailable: "사용 불가" };

function messageFrom(error) {
  const detail = error?.response?.data?.detail;
  if (typeof detail === "string") return detail;
  if (detail?.message) return detail.message;
  return error?.response?.data?.message || error?.message || "요청을 처리하지 못했습니다.";
}

function tokenValue(data, kind) {
  return data?.[`${kind}Token`] || data?.[`${kind}_token`] || "";
}

function listValue(data, key) {
  if (Array.isArray(data)) return data;
  return data?.[key] || data?.items || data?.events || [];
}

function formatDate(value) {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("ko-KR");
}

function captureValue(capture, camel, snake) {
  return capture?.[camel] ?? capture?.[snake] ?? "-";
}

function classLabel(value) {
  return classLabels[String(value || "unknown").toLowerCase()] || "Unknown";
}

function probabilityLabel(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${Math.round(number * 100)}%` : "-";
}

function RppgCaptures({ captures, loading, onRefresh, onReveal, onDelete }) {
  return (
    <section className="card rppg-card">
      <div className="card-heading">
        <div><p className="eyebrow">Camera rPPG</p><h2>얼굴 영상 측정 기록</h2></div>
        <button className="icon-button" onClick={onRefresh} aria-label="카메라 측정 기록 새로고침">↻</button>
      </div>
      <p className="rppg-privacy">기본 화면에는 요약 정보만 표시됩니다. 영상 재생에는 사유가 필요하며 모든 열람이 감사 기록에 남습니다.</p>
      {loading ? <div className="empty">카메라 측정 기록을 불러오는 중…</div> : captures.length ? (
        <div className="table-scroll">
          <table className="capture-table">
            <thead><tr><th>촬영 시각</th><th>환자</th><th>상태</th><th>품질 / HR</th><th>모델</th><th>관리</th></tr></thead>
            <tbody>{captures.map((capture) => {
              const captureId = captureValue(capture, "captureId", "capture_id") === "-" ? capture.id : captureValue(capture, "captureId", "capture_id");
              const patientId = captureValue(capture, "patientId", "patient_id");
              const quality = captureValue(capture, "qualityScore", "quality_score");
              const heartRate = captureValue(capture, "heartRateBpm", "heart_rate_bpm");
              const deletionState = captureValue(capture, "deletionState", "deletion_state");
              return <tr key={captureId}>
                <td>{formatDate(captureValue(capture, "capturedAt", "captured_at"))}</td>
                <td><code>{patientId}</code></td>
                <td><span className="badge">{capture.status || "unknown"}</span>{deletionState !== "-" && deletionState !== "active" ? <small className="capture-state">{deletionState}</small> : null}</td>
                <td>{quality} / {heartRate === "-" ? "-" : `${heartRate} bpm`}</td>
                <td>{captureValue(capture, "modelName", "model_name")}</td>
                <td className="capture-actions"><button className="secondary" onClick={() => onReveal(captureId)}>재생</button><button className="danger" onClick={() => onDelete(captureId)}>삭제</button></td>
              </tr>;
            })}</tbody>
          </table>
        </div>
      ) : <div className="empty">저장된 카메라 측정 기록이 없습니다.</div>}
    </section>
  );
}

function AuthScreen({ onAuthenticated }) {
  const [mode, setMode] = useState("login");
  const [form, setForm] = useState(initialAuth);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      let response;
      if (mode === "signup") {
        response = await api.post("/api/auth/admin/signup", {
          email: form.email.trim(),
          password: form.password,
          signupCode: form.signupCode,
        });
      }
      if (!tokenValue(response?.data, "access") || !tokenValue(response?.data, "refresh")) {
        response = await api.post("/api/auth/login", {
          email: form.email.trim(),
          password: form.password,
        });
      }
      const accessToken = tokenValue(response.data, "access");
      const refreshToken = tokenValue(response.data, "refresh");
      const role = response.data?.user?.role || response.data?.role;
      if (!accessToken || !refreshToken) throw new Error("인증 토큰이 없습니다.");
      if (role && role !== "admin") throw new Error("관리자 계정만 접근할 수 있습니다.");
      setAccessToken(accessToken);
      storeRefreshToken(refreshToken);
      onAuthenticated(response.data?.user || { email: form.email, role: "admin" });
    } catch (requestError) {
      clearSession();
      setError(messageFrom(requestError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-card">
        <div className="brand-mark">NT</div>
        <p className="eyebrow">NeuroTruth Admin</p>
        <h1>관리자 콘솔</h1>
        <p className="muted">민감정보 열람과 변경 작업은 사유와 함께 감사 기록에 남습니다.</p>
        <div className="tabs" role="tablist" aria-label="인증 방식">
          <button className={mode === "login" ? "active" : ""} onClick={() => setMode("login")} type="button">로그인</button>
          <button className={mode === "signup" ? "active" : ""} onClick={() => setMode("signup")} type="button">관리자 가입</button>
        </div>
        <form onSubmit={submit} className="stack">
          {mode === "signup" && (
            <label>관리자 가입 코드<input type="password" value={form.signupCode} onChange={(event) => setForm({ ...form, signupCode: event.target.value })} required /></label>
          )}
          <label>이메일<input type="email" autoComplete="username" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} required /></label>
          <label>비밀번호<input type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} minLength={mode === "signup" ? 12 : 1} value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} required /></label>
          {error && <p className="notice error" role="alert">{error}</p>}
          <button className="primary" disabled={busy}>{busy ? "처리 중…" : mode === "login" ? "로그인" : "가입 후 로그인"}</button>
        </form>
      </section>
    </main>
  );
}

function Timeline({ patient, entries, loading, onReveal }) {
  if (!patient) return <div className="empty">환자를 선택하면 수집 이력을 확인할 수 있습니다.</div>;
  if (loading) return <div className="empty">타임라인을 불러오는 중…</div>;
  if (!entries.length) return <div className="empty">저장된 타임라인이 없습니다.</div>;
  return (
    <div className="timeline">
      {entries.map((entry, index) => {
        const explicitResourceType = entry.resourceType || entry.resource_type;
        const resourceType = explicitResourceType || entry.type;
        const resourceId = entry.resourceId || entry.resource_id || entry.id;
        const canReveal = resourceId && revealableResourceTypes.has(resourceType);
        return (
          <article className="timeline-item" key={`${resourceType}-${resourceId || index}`}>
            <div><span className="badge">{entry.type || entry.eventType || entry.event_type || "event"}</span><time>{formatDate(entry.timestamp || entry.createdAt || entry.created_at || entry.occurredAt)}</time></div>
            <h3>{entry.title || entry.summary || entry.status || "기록"}</h3>
            {entry.description && <p>{entry.description}</p>}
            {canReveal && <button className="link-button" onClick={() => onReveal(resourceType, resourceId)}>민감정보 열람</button>}
          </article>
        );
      })}
    </div>
  );
}

function ClassTrend({ predictions }) {
  if (!predictions.length) return <div className="chart-empty">선택한 기간에 갈망 추정 기록이 없습니다.</div>;
  const yByClass = { high: 24, mid: 70, low: 116, unknown: 148 };
  const points = predictions.map((prediction, index) => ({
    x: predictions.length === 1 ? 50 : 50 + (index * 620) / (predictions.length - 1),
    y: yByClass[String(prediction.class || "unknown").toLowerCase()] ?? yByClass.unknown,
    prediction,
  }));
  const path = points.reduce((value, point, index) => {
    if (index === 0) return `M ${point.x} ${point.y}`;
    return `${value} H ${point.x} V ${point.y}`;
  }, "");
  return (
    <div className="trend-chart">
      <svg viewBox="0 0 700 180" role="img" aria-label={`갈망 추정 ${predictions.length}건의 Low, Mid, High 범주 추세`}>
        <title>갈망 추정 범주 추세</title>
        {[["High", 24], ["Mid", 70], ["Low", 116]].map(([label, y]) => (
          <g key={label}><line x1="50" x2="670" y1={y} y2={y} /><text x="6" y={Number(y) + 4}>{label}</text></g>
        ))}
        <path d={path} />
        {points.map(({ x, y, prediction }) => (
          <circle key={prediction.predictionId || `${prediction.at}-${x}`} cx={x} cy={y} r="5">
            <title>{`${formatDate(prediction.at)}: ${classLabel(prediction.class)}, ${probabilityLabel(prediction.probability)}`}</title>
          </circle>
        ))}
      </svg>
      <ul className="sr-data-list" aria-label="갈망 추정 상세 목록">
        {predictions.map((prediction) => <li key={prediction.predictionId || prediction.at}>{formatDate(prediction.at)}: {classLabel(prediction.class)} ({probabilityLabel(prediction.probability)})</li>)}
      </ul>
    </div>
  );
}

function AuqTrend({ assessments }) {
  if (!assessments.length) return <div className="chart-empty compact">선택한 기간에 AUQ 응답이 없습니다.</div>;
  const values = assessments.map((assessment) => Number(assessment.rawScore)).filter(Number.isFinite);
  const max = Math.max(...assessments.map((assessment) => Number(assessment.scaleMax) || 56), 1);
  return (
    <div className="bar-chart" aria-label={`AUQ 자기보고 ${assessments.length}건`}>
      {assessments.map((assessment, index) => {
        const score = Number(assessment.rawScore);
        const width = Number.isFinite(score) ? Math.max(2, Math.min(100, (score / max) * 100)) : 0;
        return <div className="bar-row" key={assessment.assessmentId || `${assessment.at}-${index}`}><time>{formatDate(assessment.at)}</time><span className="bar-track"><span style={{ width: `${width}%` }} /></span><strong>{Number.isFinite(score) ? score : "-"}</strong></div>;
      })}
      <p className="chart-note">AUQ는 모델의 Low/Mid/High 분류와 합산하지 않고 별도 자기보고로 표시합니다. 평균 {values.length ? (values.reduce((sum, value) => sum + value, 0) / values.length).toFixed(1) : "-"}</p>
    </div>
  );
}

function StateCard({ title, state, onReveal }) {
  if (!state) return <section className="state-card"><h3>{title}</h3><p className="muted">저장된 상태 추론이 없습니다.</p></section>;
  return (
    <section className="state-card">
      <div><h3>{title}</h3><span className={`class-pill ${String(state.state || "unknown").toLowerCase()}`}>{classLabel(state.state)}</span></div>
      <dl><div><dt>확률</dt><dd>{probabilityLabel(state.confidence)}</dd></div><div><dt>요약 상태</dt><dd>{summaryLabels[state.summaryStatus] || state.summaryStatus || "-"}</dd></div><div><dt>근거 시각</dt><dd>{formatDate(state.createdAt)}</dd></div></dl>
      <p className="privacy-note">민감 요약 원문은 이 대시보드에서 복호화하지 않습니다.</p>
      {state.inferenceId && <button type="button" className="link-button" onClick={() => onReveal("state_inference", state.inferenceId)}>사유 입력 후 상태 원문 열람</button>}
    </section>
  );
}

function PatientDashboard({ patient, value, range, onRangeChange, onReveal, loading }) {
  const dashboard = value || {};
  const predictions = Array.isArray(dashboard.predictions) ? dashboard.predictions : [];
  const assessments = Array.isArray(dashboard.assessments) ? dashboard.assessments : [];
  const events = Array.isArray(dashboard.events) ? dashboard.events : [];
  const reports = Array.isArray(dashboard.reports) ? dashboard.reports : [];
  return (
    <section className="patient-dashboard" aria-labelledby="patient-dashboard-title">
      <div className="dashboard-heading">
        <div><p className="eyebrow">Intervention overview</p><h2 id="patient-dashboard-title">{patient ? `${patient.name || patient.email || "환자"} 중재 대시보드` : "환자 중재 대시보드"}</h2><p className="muted">모델 분류, 자기보고, 대화·중재 시점을 함께 보되 치료 효과나 인과관계를 의미하지 않습니다.</p></div>
        <div className="range-tabs" role="group" aria-label="대시보드 기간">
          {dashboardRanges.map((option) => <button key={option.value} type="button" className={range === option.value ? "active" : ""} aria-pressed={range === option.value} onClick={() => onRangeChange(option.value)}>{option.label}</button>)}
        </div>
      </div>
      {!patient ? <div className="empty">환자를 선택하면 기간별 중재 현황을 확인할 수 있습니다.</div> : loading ? <div className="empty">대시보드를 불러오는 중…</div> : (
        <div className="dashboard-panels">
          <section className="dashboard-panel trend-panel"><div className="panel-title"><h3>갈망 추정 범주</h3><span>{predictions.length}건</span></div><ClassTrend predictions={predictions} /></section>
          <section className="dashboard-panel auq-panel"><div className="panel-title"><h3>AUQ 자기보고</h3><span>{assessments.length}건</span></div><AuqTrend assessments={assessments} /></section>
          <section className="dashboard-panel events-panel"><div className="panel-title"><h3>이벤트와 중재</h3><span>{events.length}건</span></div>{events.length ? <ol className="event-list">{events.map((event, index) => <li key={event.eventId || `${event.type}-${event.at}-${index}`}><span className={`event-dot ${event.type || "event"}`} /><div><strong>{eventLabels[event.type] || event.label || event.type || "이벤트"}</strong><p>{event.label && eventLabels[event.type] !== event.label ? event.label : event.sessionId ? `세션 ${event.sessionId}` : "저장된 시점 메타데이터"}</p><time>{formatDate(event.at)}</time>{event.type === "intervention" && event.eventId && <button type="button" className="link-button" onClick={() => onReveal("intervention", event.eventId)}>사유 입력 후 중재 원문 열람</button>}</div></li>)}</ol> : <div className="chart-empty compact">선택한 기간에 알림·대화·중재 이벤트가 없습니다.</div>}</section>
          <section className="dashboard-panel states-panel"><div className="panel-title"><h3>상태 추론</h3><span>메타데이터 전용</span></div><div className="state-grid"><StateCard title="최신 실시간 상태" state={dashboard.latestState} onReveal={onReveal} /><StateCard title="30일 종단 상태" state={dashboard.longitudinalState} onReveal={onReveal} /></div></section>
          <section className="dashboard-panel reports-panel"><div className="panel-title"><h3>리포트 생성 상태</h3><span>{reports.length}건</span></div>{reports.length ? <div className="report-list">{reports.map((report) => <div key={report.reportId || report.sessionId}><span className={`status-badge ${report.status}`}>{reportLabels[report.status] || report.status}</span><span>세션 {report.sessionId || "-"}</span><time>{formatDate(report.updatedAt)}</time>{report.reportId && <button type="button" className="link-button" onClick={() => onReveal("report", report.reportId)}>사유 입력 후 원문 열람</button>}</div>)}</div> : <div className="chart-empty compact">선택한 기간에 생성된 리포트가 없습니다.</div>}<p className="privacy-note">본문은 기본 대시보드 응답에 포함되지 않으며 사유 기반 감사 열람에서만 복호화합니다.</p></section>
        </div>
      )}
    </section>
  );
}

function Modal({ title, children, onClose }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="modal" role="dialog" aria-modal="true" aria-label={title} onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-heading"><h2>{title}</h2><button className="icon-button" onClick={onClose} aria-label="닫기">×</button></div>
        {children}
      </section>
    </div>
  );
}

function AdminConsole({ user, onLogout }) {
  const [patients, setPatients] = useState([]);
  const [selectedId, setSelectedId] = useState("");
  const [timeline, setTimeline] = useState([]);
  const [settings, setSettings] = useState({ interventionsEnabled: true, chatTimeoutSeconds: 3600 });
  const [loading, setLoading] = useState(true);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [dashboardRange, setDashboardRange] = useState("24h");
  const [dashboard, setDashboard] = useState(null);
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [modal, setModal] = useState(null);
  const [captures, setCaptures] = useState([]);
  const [capturesLoading, setCapturesLoading] = useState(false);
  const [rppgVisible, setRppgVisible] = useState(false);

  const selected = useMemo(() => patients.find((patient) => String(patient.id) === String(selectedId)), [patients, selectedId]);

  const loadPatients = useCallback(async () => {
    const response = await api.get("/api/admin/patients");
    const next = listValue(response.data, "patients");
    setPatients(next);
    setSelectedId((current) => current || (next[0]?.id ? String(next[0].id) : ""));
  }, []);

  const loadSettings = useCallback(async () => {
    const response = await api.get("/api/admin/settings");
    setSettings((current) => ({ ...current, ...(response.data?.settings || response.data) }));
  }, []);

  const loadCaptures = useCallback(async () => {
    setCapturesLoading(true);
    try {
      const response = await api.get("/api/admin/rppg/captures", { params: { limit: 50 } });
      setCaptures(listValue(response.data, "captures"));
    } finally {
      setCapturesLoading(false);
    }
  }, []);

  const loadRppgCapability = useCallback(async () => {
    try {
      const response = await api.get("/api/rppg/status");
      const visible = response.data?.enabled === true && (response.data?.ready === true || response.data?.available === true);
      setRppgVisible(visible);
      if (visible) await loadCaptures();
    } catch {
      setRppgVisible(false);
      setCaptures([]);
    }
  }, [loadCaptures]);

  useEffect(() => {
    Promise.all([loadPatients(), loadSettings()])
      .catch((requestError) => setError(messageFrom(requestError)))
      .finally(() => setLoading(false));
    loadRppgCapability();
  }, [loadPatients, loadRppgCapability, loadSettings]);

  useEffect(() => () => {
    if (modal?.videoUrl) URL.revokeObjectURL(modal.videoUrl);
  }, [modal?.videoUrl]);

  useEffect(() => {
    if (!selectedId) { setTimeline([]); return; }
    setTimelineLoading(true);
    setError("");
    api.get(`/api/admin/patients/${selectedId}/timeline`)
      .then((response) => setTimeline(listValue(response.data, "timeline")))
      .catch((requestError) => setError(messageFrom(requestError)))
      .finally(() => setTimelineLoading(false));
  }, [selectedId]);

  useEffect(() => {
    if (!selectedId) { setDashboard(null); return; }
    let cancelled = false;
    setDashboardLoading(true);
    api.get(`/api/admin/patients/${selectedId}/dashboard`, { params: { range: dashboardRange } })
      .then((response) => { if (!cancelled) setDashboard(response.data); })
      .catch((requestError) => { if (!cancelled) { setDashboard(null); setError(messageFrom(requestError)); } })
      .finally(() => { if (!cancelled) setDashboardLoading(false); });
    return () => { cancelled = true; };
  }, [dashboardRange, selectedId]);

  async function saveSettings(event) {
    event.preventDefault();
    setNotice(""); setError("");
    try {
      const payload = {
        interventionsEnabled: Boolean(settings.interventionsEnabled),
        chatTimeoutSeconds: Math.max(60, Number(settings.chatTimeoutSeconds) || 3600),
      };
      const response = await api.patch("/api/admin/settings", payload);
      setSettings({ ...payload, ...response.data });
      setNotice("전역 설정을 저장했습니다.");
    } catch (requestError) { setError(messageFrom(requestError)); }
  }

  function askReveal(resourceType, resourceId) {
    setModal({ type: "reveal", resourceType, resourceId, reason: "", result: null, busy: false, error: "" });
  }

  async function submitReveal(event) {
    event.preventDefault();
    setModal((current) => ({ ...current, busy: true, error: "" }));
    try {
      const response = await api.post(`/api/admin/resources/${encodeURIComponent(modal.resourceType)}/${encodeURIComponent(modal.resourceId)}/reveal`, { reason: modal.reason.trim() });
      setModal((current) => ({ ...current, busy: false, result: response.data }));
    } catch (requestError) { setModal((current) => ({ ...current, busy: false, error: messageFrom(requestError) })); }
  }

  async function submitTemporaryPassword(event) {
    event.preventDefault();
    setModal((current) => ({ ...current, busy: true, error: "" }));
    try {
      await api.post(`/api/admin/patients/${selectedId}/temporary-password`, { temporaryPassword: modal.password, reason: modal.reason.trim() });
      setModal(null); setNotice("임시 비밀번호를 지정했습니다. 다음 로그인에서 변경이 필요합니다.");
    } catch (requestError) { setModal((current) => ({ ...current, busy: false, error: messageFrom(requestError) })); }
  }

  async function submitDelete(event) {
    event.preventDefault();
    setModal((current) => ({ ...current, busy: true, error: "" }));
    try {
      await api.delete(`/api/admin/patients/${selectedId}`, { data: { confirmation: modal.confirmation, reason: modal.reason.trim() } });
      setModal(null); setSelectedId(""); setTimeline([]); setNotice("삭제 요청을 접수했습니다.");
      await loadPatients();
    } catch (requestError) { setModal((current) => ({ ...current, busy: false, error: messageFrom(requestError) })); }
  }

  function closeModal() {
    setModal(null);
  }

  function askCaptureReveal(captureId) {
    setModal({ type: "capture-reveal", captureId, reason: "", videoUrl: "", busy: false, error: "" });
  }

  async function submitCaptureReveal(event) {
    event.preventDefault();
    setModal((current) => ({ ...current, busy: true, error: "" }));
    try {
      const response = await api.post(
        `/api/admin/rppg/captures/${encodeURIComponent(modal.captureId)}/reveal-video`,
        { reason: modal.reason.trim() },
        { responseType: "blob" },
      );
      const videoUrl = URL.createObjectURL(response.data);
      setModal((current) => ({ ...current, busy: false, videoUrl }));
    } catch (requestError) {
      setModal((current) => ({ ...current, busy: false, error: messageFrom(requestError) }));
    }
  }

  function askCaptureDelete(captureId) {
    setModal({ type: "capture-delete", captureId, confirmation: "", reason: "", busy: false, error: "" });
  }

  async function submitCaptureDelete(event) {
    event.preventDefault();
    setModal((current) => ({ ...current, busy: true, error: "" }));
    try {
      await api.delete(`/api/admin/rppg/captures/${encodeURIComponent(modal.captureId)}`, {
        data: { reason: modal.reason.trim(), confirmCaptureId: modal.confirmation },
      });
      closeModal();
      setNotice("카메라 측정 기록을 삭제했고 감사 로그에 남겼습니다.");
      await loadCaptures();
    } catch (requestError) {
      setModal((current) => ({ ...current, busy: false, error: messageFrom(requestError) }));
    }
  }

  async function logout() {
    try { await api.post("/api/auth/logout", { refreshToken: getStoredRefreshToken() }); } catch { /* local logout must still complete */ }
    onLogout();
  }

  return (
    <main className="app-shell">
      <header className="topbar"><div><span className="brand-mark small">NT</span><div><strong>NeuroTruth</strong><small>관리자 콘솔</small></div></div><div className="account"><span>{user?.name || user?.email || "Administrator"}</span><button className="secondary" onClick={logout}>로그아웃</button></div></header>
      <section className="page-heading"><div><p className="eyebrow">Research operations</p><h1>환자 데이터 관리</h1><p className="muted">요약은 기본 제공되며 원문 열람과 변경은 사유가 필요합니다.</p></div><div className="status-dot"><span />보호된 연결</div></section>
      {error && <p className="notice error" role="alert">{error}</p>}
      {notice && <p className="notice success" role="status">{notice}</p>}
      <div className="dashboard-grid">
        <aside className="card patients-card"><div className="card-heading"><div><p className="eyebrow">Patients</p><h2>환자 목록</h2></div><button className="icon-button" onClick={() => { setLoading(true); loadPatients().catch((e) => setError(messageFrom(e))).finally(() => setLoading(false)); }} aria-label="새로고침">↻</button></div>{loading ? <div className="empty">불러오는 중…</div> : patients.length ? <div className="patient-list">{patients.map((patient) => <button key={patient.id} className={String(patient.id) === String(selectedId) ? "patient active" : "patient"} onClick={() => setSelectedId(String(patient.id))}><span className="avatar">{(patient.name || patient.email || "?").slice(0, 1).toUpperCase()}</span><span><strong>{patient.name || "이름 미등록"}</strong><small>{patient.email || patient.id}</small></span><em>{patient.status || "active"}</em></button>)}</div> : <div className="empty">등록된 환자가 없습니다.</div>}</aside>
        <section className="card timeline-card"><div className="card-heading"><div><p className="eyebrow">Patient timeline</p><h2>{selected?.name || "환자를 선택하세요"}</h2></div>{selected && <span className="pill">{selected.status || "active"}</span>}</div><Timeline patient={selected} entries={timeline} loading={timelineLoading} onReveal={askReveal} /></section>
        <aside className="side-stack">
          <form className="card settings-card" onSubmit={saveSettings}><div className="card-heading"><div><p className="eyebrow">Global policy</p><h2>전역 설정</h2></div></div><label className="switch-row"><span><strong>일반 개입</strong><small>안전 안내는 항상 유지됩니다.</small></span><input type="checkbox" checked={Boolean(settings.interventionsEnabled)} onChange={(event) => setSettings({ ...settings, interventionsEnabled: event.target.checked })} /></label><label>채팅 제한시간 (초)<input type="number" min="60" max="86400" step="60" value={settings.chatTimeoutSeconds} onChange={(event) => setSettings({ ...settings, chatTimeoutSeconds: event.target.value })} /></label><button className="primary">설정 저장</button></form>
          <section className="card actions-card"><div className="card-heading"><div><p className="eyebrow">Account actions</p><h2>환자 계정</h2></div></div><button className="secondary full" disabled={!selected} onClick={() => askReveal("patient_profile", selectedId)}>프로필 민감정보 열람</button><button className="secondary full" disabled={!selected} onClick={() => setModal({ type: "password", password: "", reason: "", busy: false, error: "" })}>임시 비밀번호 지정</button><button className="danger full" disabled={!selected} onClick={() => setModal({ type: "delete", confirmation: "", reason: "", busy: false, error: "" })}>환자 데이터 삭제</button></section>
        </aside>
      </div>
      <PatientDashboard patient={selected} value={dashboard} range={dashboardRange} onRangeChange={setDashboardRange} onReveal={askReveal} loading={dashboardLoading} />
      {rppgVisible && <div className="rppg-grid">
        <RppgCaptures captures={captures} loading={capturesLoading} onRefresh={() => loadCaptures().catch((requestError) => setError(messageFrom(requestError)))} onReveal={askCaptureReveal} onDelete={askCaptureDelete} />
      </div>}
      {modal?.type === "capture-reveal" && <Modal title="감사 대상 카메라 영상 재생" onClose={closeModal}>{modal.videoUrl ? <><p className="notice warning">열람 사실이 감사 로그에 기록되었습니다. 권한 있는 열람자가 재생된 영상을 기술적으로 보존할 수 있으므로 운영 정책을 준수하세요.</p><video className="capture-video" src={modal.videoUrl} controls autoPlay playsInline controlsList="nodownload" disablePictureInPicture onContextMenu={(event) => event.preventDefault()} /></> : <form className="stack" onSubmit={submitCaptureReveal}><p className="muted">구체적인 업무상 사유를 입력하세요. Backend는 이 no-store inline 응답을 위해서만 영상을 일시 복호화합니다.</p><label>재생 사유<textarea minLength={1} value={modal.reason} onChange={(event) => setModal({ ...modal, reason: event.target.value })} required /></label>{modal.error && <p className="notice error" role="alert">{modal.error}</p>}<button className="primary" disabled={modal.busy || !modal.reason.trim()}>{modal.busy ? "열람 중…" : "사유 기록 후 재생"}</button></form>}</Modal>}
      {modal?.type === "capture-delete" && <Modal title="카메라 측정 기록 삭제" onClose={closeModal}><form className="stack" onSubmit={submitCaptureDelete}><p className="notice warning">암호화 영상과 연결된 카메라 분석·예측을 삭제합니다. 민감정보가 없는 감사 기록은 남습니다.</p><label>정확한 capture UUID <small>{modal.captureId}</small><input value={modal.confirmation} onChange={(event) => setModal({ ...modal, confirmation: event.target.value })} placeholder={modal.captureId} required /></label><label>삭제 사유<textarea minLength={1} value={modal.reason} onChange={(event) => setModal({ ...modal, reason: event.target.value })} required /></label>{modal.error && <p className="notice error" role="alert">{modal.error}</p>}<button className="danger" disabled={modal.busy || modal.confirmation !== modal.captureId || !modal.reason.trim()}>{modal.busy ? "삭제 중…" : "영구 삭제 확인"}</button></form></Modal>}
      {modal?.type === "reveal" && <Modal title="민감정보 열람" onClose={() => setModal(null)}>{modal.result ? <><p className="notice warning">열람 내용은 감사 기록에 남았습니다.</p><pre className="revealed">{JSON.stringify(modal.result, null, 2)}</pre></> : <form className="stack" onSubmit={submitReveal}><p className="muted">업무상 필요한 구체적인 열람 사유를 입력하세요.</p><label>열람 사유<textarea minLength={1} value={modal.reason} onChange={(event) => setModal({ ...modal, reason: event.target.value })} required /></label>{modal.error && <p className="notice error">{modal.error}</p>}<button className="primary" disabled={modal.busy}>{modal.busy ? "열람 중…" : "사유 기록 후 열람"}</button></form>}</Modal>}
      {modal?.type === "password" && <Modal title="임시 비밀번호 지정" onClose={() => setModal(null)}><form className="stack" onSubmit={submitTemporaryPassword}><label>임시 비밀번호<input type="password" minLength={12} value={modal.password} onChange={(event) => setModal({ ...modal, password: event.target.value })} required /></label><label>변경 사유<textarea minLength={1} value={modal.reason} onChange={(event) => setModal({ ...modal, reason: event.target.value })} required /></label>{modal.error && <p className="notice error">{modal.error}</p>}<button className="primary" disabled={modal.busy}>{modal.busy ? "저장 중…" : "임시 비밀번호 지정"}</button></form></Modal>}
      {modal?.type === "delete" && <Modal title="환자 데이터 삭제" onClose={() => setModal(null)}><form className="stack" onSubmit={submitDelete}><p className="notice warning">계정이 잠기고 암호화 자료 삭제가 시작됩니다. 이 작업은 되돌릴 수 없습니다.</p><label>확인값 <small>{selectedId}</small><input value={modal.confirmation} onChange={(event) => setModal({ ...modal, confirmation: event.target.value })} placeholder={selectedId} required /></label><label>삭제 사유<textarea minLength={1} value={modal.reason} onChange={(event) => setModal({ ...modal, reason: event.target.value })} required /></label>{modal.error && <p className="notice error">{modal.error}</p>}<button className="danger" disabled={modal.busy || modal.confirmation !== selectedId}>{modal.busy ? "요청 중…" : "확인하고 삭제 요청"}</button></form></Modal>}
    </main>
  );
}

function App() {
  const [user, setUser] = useState(null);
  const [restoring, setRestoring] = useState(Boolean(getStoredRefreshToken()));

  const logout = useCallback(() => { clearSession(); setUser(null); setRestoring(false); }, []);

  useEffect(() => { setAuthFailureHandler(logout); return () => setAuthFailureHandler(null); }, [logout]);
  useEffect(() => {
    const refreshToken = getStoredRefreshToken();
    if (!refreshToken) { setRestoring(false); return; }
    api.post("/api/auth/refresh", { refreshToken }, { skipAuthRefresh: true })
      .then((response) => {
        const accessToken = tokenValue(response.data, "access");
        const rotated = tokenValue(response.data, "refresh");
        if (!accessToken) throw new Error("세션을 복원하지 못했습니다.");
        setAccessToken(accessToken); if (rotated) storeRefreshToken(rotated);
        const nextUser = response.data?.user || { role: "admin" };
        if (nextUser.role && nextUser.role !== "admin") throw new Error("관리자 계정이 아닙니다.");
        setUser(nextUser);
      })
      .catch(logout)
      .finally(() => setRestoring(false));
  }, [logout]);

  if (restoring) return <main className="auth-shell"><div className="auth-card centered">관리자 세션을 확인하는 중…</div></main>;
  return user ? <AdminConsole user={user} onLogout={logout} /> : <AuthScreen onAuthenticated={setUser} />;
}

export default App;
