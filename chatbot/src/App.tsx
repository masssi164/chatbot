import { useCallback, useEffect, useState } from 'react'
import './App.css'
import ChatHistory from './components/ChatHistory'
import ChatInput from './components/ChatInput'
import SettingsPanel from './components/SettingsPanel'
import McpServerPanel from './components/McpServerPanel'
import { useChatStore } from './store/chatStore'
import { useMcpServerStore } from './store/mcpServerStore'
import { ensureMcpSession, disconnectMcpSession } from './services/mcpClientManager'

function App() {
  const config = useChatStore((state) => state.config)
  const messages = useChatStore((state) => state.messages)
  const availableModels = useChatStore((state) => state.availableModels)
  const isLoading = useChatStore((state) => state.isLoading)
  const error = useChatStore((state) => state.error)

  const setBaseUrl = useChatStore((state) => state.setBaseUrl)
  const setApiKey = useChatStore((state) => state.setApiKey)
  const setModel = useChatStore((state) => state.setModel)
  const setSystemPrompt = useChatStore((state) => state.setSystemPrompt)
  const fetchModels = useChatStore((state) => state.fetchModels)
  const sendMessage = useChatStore((state) => state.sendMessage)
  const resetConversation = useChatStore((state) => state.resetConversation)
  const resetModels = useChatStore((state) => state.resetModels)

  const servers = useMcpServerStore((state) => state.servers)
  const activeServerId = useMcpServerStore((state) => state.activeServerId)
  const setActiveServer = useMcpServerStore((state) => state.setActiveServer)
  const setServerStatus = useMcpServerStore((state) => state.setServerStatus)
  const registerServer = useMcpServerStore((state) => state.registerServer)
  const removeServer = useMcpServerStore((state) => state.removeServer)

  const [prompt, setPrompt] = useState('')
  const [showSettings, setShowSettings] = useState(false)

  useEffect(() => {
    const trimmedBaseUrl = config.baseUrl.trim()
    if (!trimmedBaseUrl) {
      resetModels()
      return
    }

    let cancelled = false

    const run = async () => {
      const success = await fetchModels()
      if (!success && !cancelled) {
        resetModels()
      }
    }

    void run()

    return () => {
      cancelled = true
    }
  }, [config.baseUrl, config.apiKey, fetchModels, resetModels])

  useEffect(() => {
    if (!activeServerId) {
      return
    }

    const activeServer = servers.find((server) => server.id === activeServerId)
    if (!activeServer) {
      return
    }

    const trimmedUrl = activeServer.baseUrl.trim()
    if (!trimmedUrl) {
      setServerStatus(activeServerId, 'idle')
      void disconnectMcpSession(activeServerId)
      return
    }

    let cancelled = false
    setServerStatus(activeServerId, 'connecting')

    const run = async () => {
      const sessionOk = await ensureMcpSession({
        serverId: activeServerId,
        baseUrl: trimmedUrl,
        apiKey: activeServer.apiKey,
      })

      if (!cancelled) {
        setServerStatus(activeServerId, sessionOk ? 'connected' : 'error')
      }
    }

    void run()

    return () => {
      cancelled = true
      void disconnectMcpSession(activeServerId)
    }
  }, [activeServerId, servers, setServerStatus])


  const handleRefreshModels = useCallback(async () => {
    const trimmedUrl = config.baseUrl.trim()
    if (!trimmedUrl) {
      resetModels()
      return
    }
    resetModels()
    const success = await fetchModels()
    if (!success) {
      resetModels()
    }
  }, [config.baseUrl, fetchModels, resetModels])

  const handleSelectServer = useCallback(
    (serverId: string) => {
      setActiveServer(serverId)
    },
    [setActiveServer],
  )

  const handleAddServer = useCallback(
    (server: { name: string; baseUrl: string; apiKey?: string }) => {
      const trimmedUrl = server.baseUrl.trim()
      if (!trimmedUrl) {
        return
      }
      const name = server.name.trim() || `Server ${servers.length + 1}`
      const apiKey = server.apiKey?.trim() || undefined
      let id: string
      try {
        id = registerServer({ name, baseUrl: trimmedUrl, apiKey })
      } catch (error) {
        console.error('Failed to register server', error)
        return
      }
      setActiveServer(id)
      setServerStatus(id, 'idle')
    },
    [registerServer, servers.length, setActiveServer, setServerStatus],
  )

  const handleRemoveServer = useCallback(
    (serverId: string) => {
      void disconnectMcpSession(serverId)
      removeServer(serverId)
    },
    [removeServer],
  )

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1>Chatbot Console</h1>
          <p className="app-subtitle">
            Chat with OpenAI-compatible servers via the Responses API. Adjust settings to switch
            URLs or models.
          </p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="secondary"
            onClick={() => setShowSettings((current) => !current)}
          >
            {showSettings ? 'Hide settings' : 'Show settings'}
          </button>
          <button type="button" className="secondary" onClick={resetConversation}>
            Clear chat
          </button>
        </div>
      </header>

      <McpServerPanel
        servers={servers}
        activeServerId={activeServerId}
        onSelect={handleSelectServer}
        onAdd={handleAddServer}
        onRemove={handleRemoveServer}
      />

      {showSettings && (
        <SettingsPanel
          config={config}
          availableModels={availableModels}
          onBaseUrlChange={setBaseUrl}
          onApiKeyChange={setApiKey}
          onModelChange={setModel}
          onSystemPromptChange={setSystemPrompt}
          onRefreshModels={handleRefreshModels}
        />
      )}

      <main className="panel chat-panel">
        <ChatHistory messages={messages} isLoading={isLoading} />
        <ChatInput
          prompt={prompt}
          onPromptChange={setPrompt}
          onSend={sendMessage}
          isLoading={isLoading}
          currentModel={config.model}
          availableModels={availableModels}
          onModelChange={setModel}
          disabled={!config.baseUrl.trim() || !config.model.trim() || availableModels.length === 0}
        />
        {error && <p className="error-message">Request failed: {error}</p>}
      </main>
    </div>
  )
}

export default App
