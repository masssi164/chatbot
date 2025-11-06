import { useState } from "react";
import type { McpTransportType } from "../services/apiClient";
import type { ChatConfig } from "../store/chatStore";
import type { McpServer, McpServerStatus } from "../store/mcpServerStore";
import { McpCapabilitiesPanel } from "./McpCapabilitiesPanel";

const statusLabels: Record<McpServerStatus, string> = {
  idle: "Idle",
  connecting: "Connecting…",
  connected: "Connected",
  error: "Error",
};

const transportLabels: Record<McpTransportType, string> = {
  STREAMABLE_HTTP: "Streamable HTTP (modern)",
  SSE: "SSE (deprecated)",
};

interface SettingsPanelProps {
  config: ChatConfig;
  availableModels: string[];
  servers: McpServer[];
  activeServerId: string | null;
  isSyncingServers: boolean;
  onModelChange: (model: string) => void;
  onTitleModelChange: (model: string) => void;
  onRefreshModels: () => void;
  onSelectServer: (serverId: string) => void;
  onAddServer: (server: {
    name: string;
    baseUrl: string;
    apiKey?: string;
    transport: McpTransportType;
  }) => Promise<void> | void;
  onRemoveServer: (serverId: string) => Promise<void> | void;
}

function SettingsPanel({
  config,
  availableModels,
  servers,
  activeServerId,
  isSyncingServers,
  onModelChange,
  onTitleModelChange,
  onRefreshModels,
  onSelectServer,
  onAddServer,
  onRemoveServer,
}: SettingsPanelProps) {
  const [activeTab, setActiveTab] = useState<"general" | "connectors">(
    "general",
  );
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [transport, setTransport] = useState<McpTransportType>("STREAMABLE_HTTP");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const resetConnectorForm = () => {
    setName("");
    setBaseUrl("");
    setApiKey("");
    setTransport("STREAMABLE_HTTP");
  };

  const handleAddConnector = async () => {
    const trimmedUrl = baseUrl.trim();
    if (!trimmedUrl) {
      return;
    }
    setIsSubmitting(true);
    try {
      await onAddServer({
        name: name.trim() || `Connector ${servers.length + 1}`,
        baseUrl: trimmedUrl,
        apiKey: apiKey.trim() || undefined,
        transport,
      });
      resetConnectorForm();
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <section className="panel settings">
      <div className="settings-tabs">
        <button
          type="button"
          className={`settings-tab ${activeTab === "general" ? "active" : ""}`}
          onClick={() => setActiveTab("general")}
        >
          Allgemein
        </button>
        <button
          type="button"
          className={`settings-tab ${activeTab === "connectors" ? "active" : ""}`}
          onClick={() => setActiveTab("connectors")}
        >
          Konnektoren
        </button>
      </div>

      {activeTab === "general" ? (
        <div className="settings-content settings-general">
          <div className="field">
            <label>Proxy target</label>
            <p className="hint">
              Requests are routed through the backend proxy at
              {" http://localhost:8080/api/openai"}.
            </p>
          </div>
          <div className="field">
            <label htmlFor="model-select">Chat model</label>
            <div className="field-row">
              <select
                id="model-select"
                value={config.model}
                onChange={(event) => onModelChange(event.target.value)}
                disabled={availableModels.length === 0}
              >
                <option value="" disabled>
                  {availableModels.length === 0
                    ? "No models available"
                    : "Select a model"}
                </option>
                {availableModels.map((model) => (
                  <option key={model} value={model}>
                    {model}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="secondary"
                onClick={onRefreshModels}
              >
                Refresh
              </button>
            </div>
          </div>
          <div className="field">
            <label htmlFor="title-model">Titelerzeugungs-Modell</label>
            <input
              id="title-model"
              list="title-model-options"
              value={config.titleModel ?? ""}
              onChange={(event) => onTitleModelChange(event.target.value)}
              placeholder="Leer lassen, um Chat-Modell zu nutzen"
            />
            <datalist id="title-model-options">
              {availableModels.map((model) => (
                <option key={model} value={model} />
              ))}
            </datalist>
            <p className="hint">
              Wird genutzt, um aus der ersten Nutzer-Nachricht einen Chat-Titel
              zu generieren (max. 25 Tokens).
            </p>
          </div>
        </div>
      ) : (
        <div className="settings-content settings-connectors">
          <header className="connectors-header">
            <div>
              <h3>Konnektoren (MCP)</h3>
              <p>
                {isSyncingServers
                  ? "Synchronisiere…"
                  : `${servers.length} Konnektoren`}
              </p>
            </div>
          </header>
          {servers.length === 0 ? (
            <div className="connectors-empty">
              <p>
                Keine Konnektoren vorhanden. Füge einen neuen Eintrag hinzu.
              </p>
            </div>
          ) : (
            <ul className="connector-list">
              {servers.map((server) => {
                const isActive = server.id === activeServerId;
                return (
                  <li
                    key={server.id}
                    className={`connector-item ${isActive ? "active" : ""}`}
                  >
                    <button
                      type="button"
                      className="connector-select"
                      onClick={() => onSelectServer(server.id)}
                    >
                      <div className="connector-header">
                        <span className="connector-name">{server.name}</span>
                        <span className={`connector-status ${server.status}`}>
                          {statusLabels[server.status]}
                        </span>
                      </div>
                      <p className="connector-url">
                        {server.baseUrl || "Not configured"}
                      </p>
                    </button>
                    <button
                      type="button"
                      className="connector-remove"
                      onClick={() => onRemoveServer(server.id)}
                      disabled={servers.length === 1}
                    >
                      Entfernen
                    </button>
                    
                    {/* Capabilities Panel - shown when server is active and connected */}
                    {isActive && server.status === "connected" && (
                      <div className="connector-capabilities">
                        <McpCapabilitiesPanel
                          capabilities={server.capabilities ?? null}
                          isLoading={server.syncStatus === "SYNCING"}
                          serverName={server.name}
                          serverId={server.id}
                        />
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          <form
            className="connector-form"
            onSubmit={(event) => {
              event.preventDefault();
              void handleAddConnector();
            }}
          >
            <h4>Neuen Konnektor hinzufügen</h4>
            <div className="connector-grid">
              <label>
                <span>Name</span>
                <input
                  type="text"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="LM Studio"
                />
              </label>
              <label>
                <span>Base URL</span>
                <input
                  type="text"
                  value={baseUrl}
                  onChange={(event) => setBaseUrl(event.target.value)}
                  placeholder="http://localhost:1234"
                  required
                />
              </label>
              <label>
                <span>API-Key</span>
                <input
                  type="text"
                  value={apiKey}
                  onChange={(event) => setApiKey(event.target.value)}
                  placeholder="Optional"
                />
              </label>
              <label>
                <span>Transport</span>
                <select
                  value={transport}
                  onChange={(event) =>
                    setTransport(event.target.value as McpTransportType)
                  }
                >
                  <option value="STREAMABLE_HTTP">
                    {transportLabels.STREAMABLE_HTTP}
                  </option>
                  <option value="SSE">{transportLabels.SSE}</option>
                </select>
                <small className="hint">
                  Streamable HTTP ist der moderne Standard. SSE wird nach August
                  2025 nicht mehr unterstützt.
                </small>
              </label>
            </div>
            <div className="connector-actions">
              <button
                type="submit"
                className="secondary"
                disabled={isSubmitting || !baseUrl.trim()}
              >
                Hinzufügen
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  );
}

export default SettingsPanel;
