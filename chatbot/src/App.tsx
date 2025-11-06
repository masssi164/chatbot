import { useCallback, useEffect, useRef, useState } from "react";
import "./App.css";
import ChatHistory from "./components/ChatHistory";
import ChatInput, { type ChatInputHandle } from "./components/ChatInput";
import Modal from "./components/Modal";
import N8nPanel from "./components/N8nPanel";
import SettingsPanel from "./components/SettingsPanel";
import SystemPromptPanel from "./components/SystemPromptPanel";
import { ToolCallList } from "./components/ToolCallList";
import { UserApprovalDialog } from "./components/UserApprovalDialog";
import { useChatActions, useChatState } from "./hooks/useChatState";
import { useMcpActions, useMcpState } from "./hooks/useMcpState";
import type { ConversationStatus } from "./services/apiClient";

/**
 * Get user-friendly label for conversation status (matches backend ConversationStatus enum)
 */
function getStatusLabel(status: ConversationStatus, isStreaming: boolean): string {
  if (isStreaming) {
    return "Streaming...";
  }
  switch (status) {
    case "COMPLETED":
      return "✓ Completed";
    case "INCOMPLETE":
      return "⚠️ Incomplete";
    case "FAILED":
      return "✗ Failed";
    case "CREATED":
    case "STREAMING":
    default:
      return "Idle";
  }
}

function App() {
  // Chat state and actions (grouped via custom hooks)
  const chatState = useChatState();
  const chatActions = useChatActions();
  
  // MCP state and actions (grouped via custom hooks)
  const mcpState = useMcpState();
  const mcpActions = useMcpActions();

  const [prompt, setPrompt] = useState("");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [showSidebar, setShowSidebar] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showAutomations, setShowAutomations] = useState(false);
  const endRef = useRef<HTMLDivElement | null>(null);
  const chatInputRef = useRef<ChatInputHandle | null>(null);

  useEffect(() => {
    void chatActions.ensureConversation();
    void chatActions.loadConversations();
    void chatActions.fetchModels();
    void mcpActions.loadServers();
    mcpActions.connectToStatusStream();

    return () => {
      mcpActions.disconnectFromStatusStream();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Run only once on mount

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [chatState.messages]);

  const handleNewChat = useCallback(async () => {
    chatActions.reset();
    await chatActions.ensureConversation();
    setShowSidebar(false);
  }, [chatActions]);

  const handleLoadConversation = useCallback(async (id: number) => {
    await chatActions.loadConversation(id);
    setShowSidebar(false);
  }, [chatActions]);

  const formatTimestamp = (timestamp: string) => {
    const date = new Date(timestamp);
    return date.toLocaleDateString() + " " + date.toLocaleTimeString();
  };

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1>Chat Console</h1>
          {chatState.conversationTitle && <p className="conversation-title">{chatState.conversationTitle}</p>}
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="secondary"
            onClick={() => setShowSidebar(!showSidebar)}
            title="Show chat history"
          >
            Chat History {chatState.conversationSummaries.length > 0 && `(${chatState.conversationSummaries.length})`}
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => void handleNewChat()}
            title="Start a new chat (Cmd/Ctrl+Alt+N)"
          >
            New Chat
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setShowAutomations(true)}
            title="n8n Workflow Automations"
          >
            Automations
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setShowSettings(true)}
            title="Application settings"
          >
            Settings
          </button>
        </div>
        <div className="status-bar">
          {chatState.isStreaming ? (
            <span className="status streaming">Streaming…</span>
          ) : (
            <span className={`status ${chatState.conversationStatus.toLowerCase()}`}>
              {getStatusLabel(chatState.conversationStatus, chatState.isStreaming)}
            </span>
          )}
          {chatState.completionReason && chatState.conversationStatus !== "COMPLETED" && (
            <span className="status-detail">{chatState.completionReason}</span>
          )}
          {chatState.streamError && <span className="status error">{chatState.streamError}</span>}
          {submitError && <span className="status error">{submitError}</span>}
        </div>
      </header>

      {showSidebar && (
        <Modal title="Chat History" onClose={() => setShowSidebar(false)}>
          <div className="chat-history-list">
            {chatState.conversationSummaries.length === 0 ? (
              <p>No previous conversations</p>
            ) : (
              <ul>
                {chatState.conversationSummaries.map((conv) => (
                  <li 
                    key={conv.id} 
                    className={chatState.conversationId === conv.id ? "active" : ""}
                    style={{ padding: "8px", borderBottom: "1px solid #eee" }}
                  >
                    <button
                      onClick={() => void handleLoadConversation(conv.id)}
                      style={{ 
                        width: "100%", 
                        textAlign: "left", 
                        background: "none", 
                        border: "none", 
                        cursor: "pointer",
                        padding: "0"
                      }}
                    >
                      <div>
                        <strong>{conv.title || `Conversation ${conv.id}`}</strong>
                        <span style={{ fontSize: "0.85em", color: "#666", marginLeft: "8px" }}>
                          {conv.messageCount} messages
                        </span>
                      </div>
                      <div style={{ fontSize: "0.8em", color: "#999" }}>
                        {formatTimestamp(conv.updatedAt)}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Modal>
      )}

      {showSettings && (
        <Modal title="Settings" onClose={() => setShowSettings(false)}>
          <SettingsPanel
            config={{
              model: chatState.model,
              titleModel: undefined, // TODO: Add titleModel to chatStore
            }}
            availableModels={chatState.availableModels}
            servers={mcpState.servers}
            activeServerId={mcpState.activeServerId}
            isSyncingServers={mcpState.isSyncing}
            onModelChange={chatActions.setModel}
            onTitleModelChange={(titleModel) => {
              // TODO: Implement titleModel in chatStore
              console.log("Title model changed:", titleModel);
            }}
            onRefreshModels={() => {
              void chatActions.fetchModels();
            }}
            onSelectServer={mcpActions.setActiveServer}
            onAddServer={async (server) => {
              await mcpActions.registerServer({
                ...server,
                transport: server.transport || "STREAMABLE_HTTP",
              });
            }}
            onRemoveServer={mcpActions.removeServer}
          />
          
          <div style={{ borderTop: "2px solid #ddd", marginTop: "2rem", paddingTop: "1.5rem" }}>
            <SystemPromptPanel 
              value={chatState.systemPrompt} 
              onChange={chatActions.setSystemPrompt}
            />
          </div>
        </Modal>
      )}

      {showAutomations && (
        <Modal title="n8n Automations" onClose={() => setShowAutomations(false)}>
          <N8nPanel onClose={() => setShowAutomations(false)} />
        </Modal>
      )}

      <main className="chat-main">
        <section className="chat-transcript" aria-live="polite">
          <ChatHistory 
            messages={chatState.messages} 
            toolCalls={chatState.toolCalls}
            isLoading={chatState.isStreaming}
          />
          
          {/* Approval Request Dialog */}
          {chatState.pendingApprovalRequest && (
            <UserApprovalDialog
              request={chatState.pendingApprovalRequest}
              onApprove={(remember) => chatActions.sendApprovalResponse(true, remember)}
              onDeny={(remember) => chatActions.sendApprovalResponse(false, remember)}
            />
          )}
          
          <div ref={endRef} />
        </section>

        <aside className="tool-panel">
          <h2>Tool Calls</h2>
          
          {/* New detailed tool call view with expandable DTOs */}
          <ToolCallList conversationId={chatState.conversationId} />
          
          {/* Legacy simple tool call list (keeping for now) */}
          {chatState.toolCalls.length === 0 ? (
            <p className="tool-empty">No tool activity yet.</p>
          ) : (
            <ul className="tool-list">
              {chatState.toolCalls.map((tool) => (
                <li key={tool.itemId} className={`tool tool-${tool.status}`}>
                  <div className="tool-heading">
                    <span className="tool-name">{tool.name ?? tool.itemId}</span>
                    <span className="tool-status">{tool.status.replace("_", " ")}</span>
                  </div>
                  {tool.arguments && (
                    <details className="tool-section" open={tool.status === "in_progress"}>
                      <summary>Arguments</summary>
                      <pre>{tool.arguments}</pre>
                    </details>
                  )}
                  {tool.result && (
                    <details className="tool-section" open={tool.status === "completed"}>
                      <summary>Result</summary>
                      <pre>{tool.result}</pre>
                    </details>
                  )}
                  {tool.error && (
                    <p className="tool-error">{tool.error}</p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </aside>
      </main>

      <ChatInput
        ref={chatInputRef}
        prompt={prompt}
        onPromptChange={setPrompt}
        onSend={async (message) => {
          setSubmitError(null);
          try {
            await chatActions.ensureConversation();
            await chatActions.sendMessage(message);
          } catch (error) {
            console.error("Submit error:", error);
            setSubmitError(error instanceof Error ? error.message : "Unknown error");
          }
        }}
        onAbort={chatActions.abortStreaming}
        isLoading={chatState.isStreaming}
        currentModel={chatState.model}
        availableModels={chatState.availableModels}
        onModelChange={chatActions.setModel}
        parameters={{
          temperature: chatState.temperature,
          maxTokens: chatState.maxTokens,
          topP: chatState.topP,
          presencePenalty: chatState.presencePenalty,
          frequencyPenalty: chatState.frequencyPenalty,
        }}
        onParametersChange={{
          setTemperature: chatActions.setTemperature,
          setMaxTokens: chatActions.setMaxTokens,
          setTopP: chatActions.setTopP,
          setPresencePenalty: chatActions.setPresencePenalty,
          setFrequencyPenalty: chatActions.setFrequencyPenalty,
        }}
      />
      
      {submitError && (
        <div className="error-message" style={{ padding: "8px", background: "#fee", color: "#c00", margin: "8px" }}>
          {submitError}
        </div>
      )}
    </div>
  );
}

export default App;
