import type { ChatConfig } from '../store/chatStore'

interface SettingsPanelProps {
  config: ChatConfig
  availableModels: string[]
  onBaseUrlChange: (url: string) => void
  onApiKeyChange: (key: string) => void
  onModelChange: (model: string) => void
  onSystemPromptChange: (prompt: string) => void
  onRefreshModels: () => void
}

export function SettingsPanel({
  config,
  availableModels,
  onBaseUrlChange,
  onApiKeyChange,
  onModelChange,
  onSystemPromptChange,
  onRefreshModels,
}: SettingsPanelProps) {
  return (
    <section className="panel settings">
      <div className="field">
        <label htmlFor="base-url">OpenAI API base URL</label>
        <input
          id="base-url"
          type="text"
          value={config.baseUrl}
          placeholder="http://localhost:1234/v1"
          onChange={(event) => onBaseUrlChange(event.target.value)}
        />
        <p className="hint">
          Provide the Responses endpoint root. Relative paths resolve against the current origin.
        </p>
      </div>
      <div className="field">
        <label htmlFor="api-key">API key</label>
        <input
          id="api-key"
          type="password"
          value={config.apiKey ?? ''}
          onChange={(event) => onApiKeyChange(event.target.value)}
          placeholder="Optional"
        />
        <p className="hint">Stored locally in your browser only.</p>
      </div>
      <div className="field">
        <label htmlFor="model-select">Model</label>
        <div className="field-row">
          <select
            id="model-select"
            value={config.model}
            onChange={(event) => onModelChange(event.target.value)}
            disabled={availableModels.length === 0}
          >
            <option value="" disabled>
              {availableModels.length === 0 ? 'No models available' : 'Select a model'}
            </option>
            {availableModels.map((model) => (
              <option key={model} value={model}>
                {model}
              </option>
            ))}
          </select>
          <button type="button" className="secondary" onClick={onRefreshModels}>
            Refresh
          </button>
        </div>
      </div>
      <div className="field field-full">
        <label htmlFor="system-prompt">System prompt</label>
        <textarea
          id="system-prompt"
          value={config.systemPrompt}
          rows={3}
          onChange={(event) => onSystemPromptChange(event.target.value)}
        />
      </div>
    </section>
  )
}

export default SettingsPanel
