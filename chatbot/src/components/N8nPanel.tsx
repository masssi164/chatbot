import type { FormEvent } from "react";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  useN8nStore,
  type BackendN8nConnectionRequest,
} from "../store/n8nStore";

function formatDate(timestamp?: number | null) {
  if (!timestamp) {
    return "—";
  }
  return new Date(timestamp).toLocaleString();
}

function N8nPanel() {
  const navigate = useNavigate();
  const baseUrl = useN8nStore((state) => state.baseUrl);
  const configured = useN8nStore((state) => state.configured);
  const updatedAt = useN8nStore((state) => state.updatedAt);
  const status = useN8nStore((state) => state.status);
  const isLoading = useN8nStore((state) => state.isLoading);
  const isSaving = useN8nStore((state) => state.isSaving);
  const isTesting = useN8nStore((state) => state.isTesting);
  const workflows = useN8nStore((state) => state.workflows);
  const isLoadingWorkflows = useN8nStore((state) => state.isLoadingWorkflows);
  const error = useN8nStore((state) => state.error);
  const loadConnection = useN8nStore((state) => state.loadConnection);
  const updateConnection = useN8nStore((state) => state.updateConnection);
  const testConnection = useN8nStore((state) => state.testConnection);
  const loadWorkflows = useN8nStore((state) => state.loadWorkflows);
  const clearStatus = useN8nStore((state) => state.clearStatus);

  const [formBaseUrl, setFormBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [feedback, setFeedback] = useState<string | null>(null);

  useEffect(() => {
    void loadConnection();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    setFormBaseUrl(baseUrl ?? "");
    if (configured) {
      void loadWorkflows({ limit: 25 });
    }
  }, [baseUrl, configured, loadWorkflows]);

  useEffect(() => {
    if (!feedback) {
      return;
    }
    const timeout = window.setTimeout(() => setFeedback(null), 4000);
    return () => window.clearTimeout(timeout);
  }, [feedback]);

  const connectionStateLabel = useMemo(() => {
    if (isLoading) {
      return "Loading connection…";
    }
    if (!configured) {
      return "Not connected";
    }
    return "Connected";
  }, [configured, isLoading]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    clearStatus();
    setFeedback(null);
    const trimmedBaseUrl = formBaseUrl.trim();
    const trimmedKey = apiKey.trim();
    if (!trimmedBaseUrl || !trimmedKey) {
      setFeedback("Please provide both a base URL and API key.");
      return;
    }

    const payload: BackendN8nConnectionRequest = {
      baseUrl: trimmedBaseUrl,
      apiKey: trimmedKey,
    };
    const success = await updateConnection(payload);
    if (success) {
      setFeedback("Connection settings updated.");
      setApiKey("");
    }
  };

  const handleTestConnection = async () => {
    clearStatus();
    setFeedback(null);
    await testConnection();
  };

  return (
    <section className="panel n8n-panel">
      <header className="n8n-panel-header">
        <div>
          <h2>n8n Automations</h2>
          <p>Connect your n8n instance to manage workflows alongside your chats.</p>
        </div>
        <button type="button" className="secondary" onClick={() => navigate("/")}>
          Back to chats
        </button>
      </header>

      <div className="n8n-grid">
        <form className="n8n-connection-form" onSubmit={handleSubmit}>
          <div className="field">
            <label htmlFor="n8n-base-url">Base URL</label>
            <input
              id="n8n-base-url"
              type="url"
              value={formBaseUrl}
              onChange={(event) => setFormBaseUrl(event.target.value)}
              placeholder="https://example.com"
              autoComplete="off"
              required
            />
            <p className="hint">
              Provide the public URL of your n8n instance. The path <code>/api/v1</code> is appended if missing.
            </p>
          </div>

          <div className="field">
            <label htmlFor="n8n-api-key">API key</label>
            <input
              id="n8n-api-key"
              type="password"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              placeholder="Paste API key"
              autoComplete="off"
              required
            />
            <p className="hint">
              Keys are encrypted in the backend. The value is never stored in the browser.
            </p>
          </div>

          <div className="n8n-actions">
            <button type="submit" disabled={isSaving}>
              {isSaving ? "Saving…" : "Save connection"}
            </button>
            <button
              type="button"
              className="secondary"
              onClick={() => void handleTestConnection()}
              disabled={isTesting || !configured}
            >
              {isTesting ? "Testing…" : "Test connection"}
            </button>
          </div>
          {feedback && <p className="info-message" role="status">{feedback}</p>}
          {error && <p className="error-message">{error}</p>}
          {status && (
            <p
              className={`status-message ${status.connected ? "ok" : "error"}`}
              role="status"
            >
              {status.message}
            </p>
          )}
        </form>

        <aside className="n8n-connection-status">
          <h3>Status</h3>
          <dl>
            <div>
              <dt>Connection</dt>
              <dd>{connectionStateLabel}</dd>
            </div>
            <div>
              <dt>Configured URL</dt>
              <dd>{baseUrl || "—"}</dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>{formatDate(updatedAt)}</dd>
            </div>
          </dl>
        </aside>
      </div>

      <section className="n8n-workflows">
        <header className="n8n-workflows-header">
          <div>
            <h3>Workflows</h3>
            <p>
              {isLoadingWorkflows
                ? "Loading…"
                : configured
                  ? `${workflows.length} workflow${workflows.length === 1 ? "" : "s"}`
                  : "Configure the connection to load workflows."}
            </p>
          </div>
          <div className="n8n-workflows-actions">
            <button
              type="button"
              className="secondary"
              onClick={() => void loadWorkflows({ limit: 25 })}
              disabled={!configured || isLoadingWorkflows}
            >
              Refresh
            </button>
          </div>
        </header>

        {!configured ? (
          <p className="hint">Provide connection details to inspect workflows from n8n.</p>
        ) : workflows.length === 0 ? (
          <p className="hint">No workflows found for this instance.</p>
        ) : (
          <ul className="n8n-workflow-list">
            {workflows.map((workflow) => (
              <li key={workflow.id} className="n8n-workflow-item">
                <div>
                  <h4>{workflow.name}</h4>
                  <p className="meta">
                    <span className={`badge ${workflow.active ? "active" : "inactive"}`}>
                      {workflow.active ? "Active" : "Inactive"}
                    </span>
                    <span>Last change: {formatDate(workflow.updatedAt)}</span>
                  </p>
                </div>
                {workflow.tagIds.length > 0 && (
                  <div className="tags">
                    {workflow.tagIds.map((tagId) => (
                      <span key={tagId}>{tagId}</span>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  );
}

export default N8nPanel;
