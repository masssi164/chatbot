import { useState } from 'react'
import type { McpServer, McpServerStatus } from '../store/mcpServerStore'

const statusLabels: Record<McpServerStatus, string> = {
  idle: 'Idle',
  connecting: 'Connecting…',
  connected: 'Connected',
  error: 'Error',
}

interface McpServerPanelProps {
  servers: McpServer[]
  activeServerId: string | null
  onSelect: (serverId: string) => void
  onAdd: (server: { name: string; baseUrl: string; apiKey?: string }) => void
  onRemove: (serverId: string) => void
}

export function McpServerPanel({
  servers,
  activeServerId,
  onSelect,
  onAdd,
  onRemove,
}: McpServerPanelProps) {
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')

  return (
    <section className="panel server-panel">
      <header className="server-panel-header">
        <h2>Servers</h2>
        <p>Currently configured MCP connections.</p>
      </header>
      <ul className="server-list">
        {servers.map((server) => {
          const isActive = server.id === activeServerId
          return (
            <li key={server.id} className={`server-item ${isActive ? 'active' : ''}`}>
              <div className="server-item-content">
                <button type="button" className="server-select" onClick={() => onSelect(server.id)}>
                  <div className="server-item-header">
                    <span className="server-name">{server.name}</span>
                    <span className={`server-status ${server.status}`}>
                      {statusLabels[server.status]}
                    </span>
                  </div>
                  <p className="server-url">{server.baseUrl || 'Not configured'}</p>
                </button>
                <button
                  type="button"
                  className="server-remove"
                  onClick={() => onRemove(server.id)}
                  disabled={servers.length === 1}
                  aria-label={`Remove ${server.name}`}
                >
                  Remove
                </button>
              </div>
            </li>
          )
        })}
      </ul>
      <form
        className="server-form"
        onSubmit={(event) => {
          event.preventDefault()
          const trimmedUrl = baseUrl.trim()
          if (!trimmedUrl) {
            return
          }
          onAdd({ name, baseUrl: trimmedUrl, apiKey })
          setName('')
          setBaseUrl('')
          setApiKey('')
        }}
      >
        <h3>Add Server</h3>
        <div className="server-form-grid">
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
              placeholder="http://localhost:1234/v1"
              required
            />
          </label>
          <label>
            <span>API key</span>
            <input
              type="text"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              placeholder="Optional"
            />
          </label>
        </div>
        <div className="server-form-actions">
          <button type="submit" className="secondary" disabled={!baseUrl.trim()}>
            Add server
          </button>
        </div>
      </form>
    </section>
  )
}

export default McpServerPanel
