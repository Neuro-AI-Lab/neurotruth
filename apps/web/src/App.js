import { useEffect, useState } from "react";

function App() {
  const [status, setStatus] = useState("checking");

  useEffect(() => {
    fetch("/api/users")
      .then((response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return response.json();
      })
      .then(() => setStatus("connected"))
      .catch(() => setStatus("backend unavailable"));
  }, []);

  return (
    <main className="shell">
      <section className="panel">
        <div>
          <p className="eyebrow">NeuroTruth</p>
          <h1>Craving intervention stack</h1>
          <p className="copy">
            Backend, Bedrock intervention, mobile endpoints, and Postgres are
            organized under the unified app layout.
          </p>
        </div>
        <dl className="status">
          <div>
            <dt>Web</dt>
            <dd>running</dd>
          </div>
          <div>
            <dt>Backend proxy</dt>
            <dd>{status}</dd>
          </div>
        </dl>
      </section>
    </main>
  );
}

export default App;
