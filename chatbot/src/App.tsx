import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
    Navigate,
    Outlet,
    Route,
    Routes,
    useNavigate,
    useParams,
} from "react-router-dom";
import "./App.css";
import ChatHistory from "./components/ChatHistory";
import ChatInput, { type ChatInputHandle } from "./components/ChatInput";
import ChatSidebar from "./components/ChatSidebar";
import Modal from "./components/Modal";
import N8nPanel from "./components/N8nPanel";
import SettingsPanel from "./components/SettingsPanel";
import { McpCapabilitiesPanel } from "./components/McpCapabilitiesPanel";
import {
    disconnectMcpSession,
    ensureMcpSession,
} from "./services/mcpClientManager";
import { useChatStore } from "./store/chatStore";
import { useMcpServerStore } from "./store/mcpServerStore";

function ChatRoute() {
  const { chatId } = useParams<{ chatId?: string }>();
  const navigate = useNavigate();

  const messages = useChatStore((state) => state.messages);
  const isLoading = useChatStore((state) => state.isLoading);
  const error = useChatStore((state) => state.error);
  const sendMessage = useChatStore((state) => state.sendMessage);
  const config = useChatStore((state) => state.config);
  const setModel = useChatStore((state) => state.setModel);
  const availableModels = useChatStore((state) => state.availableModels);
  const currentChatId = useChatStore((state) => state.currentChatId);
  const loadChat = useChatStore((state) => state.loadChat);
  const resetConversation = useChatStore((state) => state.resetConversation);
  const setTemperature = useChatStore((state) => state.setTemperature);
  const setMaxTokens = useChatStore((state) => state.setMaxTokens);
  const setTopP = useChatStore((state) => state.setTopP);
  const setPresencePenalty = useChatStore((state) => state.setPresencePenalty);
  const setFrequencyPenalty = useChatStore((state) => state.setFrequencyPenalty);
  const currentSystemPrompt = useChatStore((state) => state.currentSystemPrompt);
  const setCurrentSystemPrompt = useChatStore((state) => state.setCurrentSystemPrompt);
  const currentTitleModel = useChatStore((state) => state.currentTitleModel);
  const setCurrentTitleModel = useChatStore((state) => state.setCurrentTitleModel);
  const saveCurrentChatMetadata = useChatStore((state) => state.saveCurrentChatMetadata);
  const hasUnsavedSystemPrompt = useChatStore((state) => state.hasUnsavedSystemPrompt);
  const hasUnsavedTitleModel = useChatStore((state) => state.hasUnsavedTitleModel);

  const [prompt, setPrompt] = useState("");
  const [clipboardMessage, setClipboardMessage] = useState<string | null>(null);
  const chatInputRef = useRef<ChatInputHandle>(null);

  const defaultTitleModelLabel = useMemo(() => {
    const trimmed = config.titleModel?.trim();
    return trimmed && trimmed.length ? trimmed : "chat model";
  }, [config.titleModel]);

  const handleSaveSystemPrompt = useCallback(() => {
    void saveCurrentChatMetadata();
  }, [saveCurrentChatMetadata]);

  const handleTitleModelChange = useCallback(
    (value: string) => {
      const trimmed = value.trim();
      const normalized = trimmed.length ? trimmed : null;
      setCurrentTitleModel(normalized);
      if (currentChatId) {
        void saveCurrentChatMetadata();
      }
    },
    [currentChatId, saveCurrentChatMetadata, setCurrentTitleModel],
  );

  useEffect(() => {
    if (chatId) {
      if (chatId !== currentChatId) {
        let cancelled = false;
        const run = async () => {
          const success = await loadChat(chatId);
          if (!success && !cancelled) {
            navigate("/", { replace: true });
          }
        };
        void run();
        return () => {
          cancelled = true;
        };
      }
      return;
    }
    resetConversation();
  }, [chatId, currentChatId, loadChat, resetConversation, navigate]);

  useEffect(() => {
    if (!chatId && currentChatId) {
      navigate(`/chat/${currentChatId}`, { replace: true });
    }
  }, [chatId, currentChatId, navigate]);

  useEffect(() => {
    if (!clipboardMessage) {
      return;
    }
    const timeout = window.setTimeout(() => {
      setClipboardMessage(null);
    }, 2500);
    return () => {
      window.clearTimeout(timeout);
    };
  }, [clipboardMessage]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.defaultPrevented) {
        return;
      }

      const key = event.key.toLowerCase();
      const isMeta = event.metaKey || event.ctrlKey;

      if (event.shiftKey && !isMeta && key === "escape") {
        event.preventDefault();
        chatInputRef.current?.focusPrompt();
        return;
      }

      if (event.shiftKey && isMeta && key === "c") {
        event.preventDefault();
        const lastMessage = messages[messages.length - 1];
        if (!lastMessage) {
          setClipboardMessage("No messages available to copy yet.");
          return;
        }
        if (!navigator.clipboard || typeof navigator.clipboard.writeText !== "function") {
          setClipboardMessage("Clipboard API unavailable in this browser.");
          return;
        }
        void navigator.clipboard
          .writeText(lastMessage.content)
          .then(() => setClipboardMessage("Last message copied to clipboard."))
          .catch(() => setClipboardMessage("Copy failed. Check browser permissions."));
      }
    };

    window.addEventListener("keydown", handler);
    return () => {
      window.removeEventListener("keydown", handler);
    };
  }, [messages]);

  return (
    <>
      <section className="chat-settings-panel">
        <div className="field">
          <label htmlFor="chat-system-prompt">System prompt</label>
          <textarea
            id="chat-system-prompt"
            value={currentSystemPrompt}
            onChange={(event) => setCurrentSystemPrompt(event.target.value)}
            rows={3}
            placeholder="Enter a system instruction for this chat"
          />
          <div className="chat-settings-actions">
            <span
              className="chat-settings-status"
              data-state={
                currentChatId
                  ? hasUnsavedSystemPrompt
                    ? "dirty"
                    : "clean"
                  : "idle"
              }
            >
              {currentChatId
                ? hasUnsavedSystemPrompt
                  ? "Unsaved changes"
                  : "Saved"
                : "No active chat"}
            </span>
            <button
              type="button"
              className="secondary"
              onClick={handleSaveSystemPrompt}
              disabled={!currentChatId || !hasUnsavedSystemPrompt}
            >
              Save prompt
            </button>
          </div>
        </div>
        <div className="field">
          <label htmlFor="chat-title-model">Title generation model</label>
          <select
            id="chat-title-model"
            value={currentTitleModel ?? ""}
            onChange={(event) => handleTitleModelChange(event.target.value)}
            disabled={availableModels.length === 0}
            aria-busy={currentChatId && hasUnsavedTitleModel ? true : undefined}
          >
            <option value="">
              {`Default (${defaultTitleModelLabel})`}
            </option>
            {availableModels.map((model) => (
              <option key={model} value={model}>
                {model}
              </option>
            ))}
          </select>
          <p className="hint">If set, titles are generated with this model instead of the default.</p>
        </div>
      </section>
      <ChatHistory messages={messages} isLoading={isLoading} />
      <ChatInput
        ref={chatInputRef}
        prompt={prompt}
        onPromptChange={setPrompt}
        onSend={async (value) => {
          await sendMessage(value);
          setPrompt("");
        }}
        isLoading={isLoading}
        currentModel={config.model}
        availableModels={availableModels}
        onModelChange={setModel}
        parameters={{
          temperature: config.temperature,
          maxTokens: config.maxTokens,
          topP: config.topP,
          presencePenalty: config.presencePenalty,
          frequencyPenalty: config.frequencyPenalty,
        }}
        onParametersChange={{
          setTemperature,
          setMaxTokens,
          setTopP,
          setPresencePenalty,
          setFrequencyPenalty,
        }}
      />
      {clipboardMessage && (
        <p className="info-message" role="status">
          {clipboardMessage}
        </p>
      )}
      {error && <p className="error-message">Request failed: {error}</p>}
    </>
  );
}

function ChatLayout() {
  const navigate = useNavigate();

  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isShortcutsOpen, setIsShortcutsOpen] = useState(false);

  const isApplePlatform = useMemo(() => {
    if (typeof navigator === "undefined") {
      return false;
    }
    const platform = navigator.platform ?? navigator.userAgent;
    return /mac|iphone|ipad|ipod/i.test(platform);
  }, []);
  const config = useChatStore((state) => state.config);
  const setModel = useChatStore((state) => state.setModel);
  const setTitleModel = useChatStore((state) => state.setTitleModel);
  const fetchModels = useChatStore((state) => state.fetchModels);
  const resetModels = useChatStore((state) => state.resetModels);
  const refreshChats = useChatStore((state) => state.refreshChats);
  const chatSummaries = useChatStore((state) => state.chatSummaries);
  const isSyncingChats = useChatStore((state) => state.isSyncing);
  const currentChatId = useChatStore((state) => state.currentChatId);
  const deleteChat = useChatStore((state) => state.deleteChat);
  const availableModels = useChatStore((state) => state.availableModels);
  const createNewChat = useChatStore((state) => state.createNewChat);
  const updateChatMetadata = useChatStore((state) => state.updateChatMetadata);
  const resetConversation = useChatStore((state) => state.resetConversation);
  const newChatShortcutHint = useMemo(
    () => (isApplePlatform ? "⌘⌥N" : "Ctrl+Alt+N"),
    [isApplePlatform],
  );
  const focusShortcutHint = "Shift+Esc";
  const shortcutItems = useMemo(
    () => [
      {
        description: "Start a new chat",
        mac: "⌘⌥N",
        other: "Ctrl+Alt+N",
      },
      {
        description: "Focus the message input",
        mac: focusShortcutHint,
        other: focusShortcutHint,
      },
      {
        description: "Copy the last message to the clipboard",
        mac: "⌘⇧C",
        other: "Ctrl+Shift+C",
      },
      {
        description: "Close dialogs",
        mac: "Esc",
        other: "Esc",
      },
    ],
    [focusShortcutHint],
  );

  const servers = useMcpServerStore((state) => state.servers);
  const activeServerId = useMcpServerStore((state) => state.activeServerId);
  const setActiveServer = useMcpServerStore((state) => state.setActiveServer);
  const registerServer = useMcpServerStore((state) => state.registerServer);
  const removeServer = useMcpServerStore((state) => state.removeServer);
  const setServerStatus = useMcpServerStore((state) => state.setServerStatus);
  const loadServers = useMcpServerStore((state) => state.loadServers);
  const loadCapabilities = useMcpServerStore((state) => state.loadCapabilities);
  const isSyncingServers = useMcpServerStore((state) => state.isSyncing);

  const activeServer = servers.find((s) => s.id === activeServerId);
  const [isLoadingCapabilities, setIsLoadingCapabilities] = useState(false);

  useEffect(() => {
    void refreshChats();
    void loadServers();
  }, [refreshChats, loadServers]);

  // Load capabilities when active server is connected
  useEffect(() => {
    if (!activeServerId || !activeServer || activeServer.status !== "connected") {
      return;
    }

    if (activeServer.capabilities) {
      return; // Already loaded
    }

    setIsLoadingCapabilities(true);
    void loadCapabilities(activeServerId).finally(() => {
      setIsLoadingCapabilities(false);
    });
  }, [activeServerId, activeServer, loadCapabilities]);

  useEffect(() => {
    let cancelled = false;

    const run = async () => {
      const success = await fetchModels();
      if (!success && !cancelled) {
        resetModels();
      }
    };

    void run();

    return () => {
      cancelled = true;
    };
  }, [fetchModels, resetModels]);

  useEffect(() => {
    if (!activeServerId) {
      return;
    }

    const activeServer = servers.find((server) => server.id === activeServerId);
    if (!activeServer) {
      return;
    }

    const trimmedUrl = activeServer.baseUrl.trim();
    if (!trimmedUrl) {
      void setServerStatus(activeServerId, "idle");
      void disconnectMcpSession(activeServerId);
      return;
    }

    let cancelled = false;
    void setServerStatus(activeServerId, "connecting");

    const run = async () => {
      const sessionOk = await ensureMcpSession({
        serverId: activeServerId,
        baseUrl: trimmedUrl,
        apiKey: activeServer.apiKey,
      });

      if (!cancelled) {
        void setServerStatus(activeServerId, sessionOk ? "connected" : "error");
      }
    };

    void run();

    return () => {
      cancelled = true;
      void disconnectMcpSession(activeServerId);
    };
    // 🔧 FIX: setServerStatus removed from dependencies to prevent infinite loop
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeServerId, servers]);

  const handleRefreshModels = useCallback(async () => {
    resetModels();
    const success = await fetchModels();
    if (!success) {
      resetModels();
    }
  }, [fetchModels, resetModels]);

  const handleNewChat = useCallback(async () => {
    resetConversation();
    navigate("/", { replace: true });
    await createNewChat();
  }, [createNewChat, navigate, resetConversation]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.defaultPrevented) {
        return;
      }
      const isModifierPressed = event.metaKey || event.ctrlKey;
      if (isModifierPressed && event.altKey && event.key.toLowerCase() === "n") {
        event.preventDefault();
        void handleNewChat();
      }
    };

    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [handleNewChat]);

  const handleRenameChat = useCallback(
    async (chatId: string, title: string | null) => {
      await updateChatMetadata(chatId, { title: title ?? null });
    },
    [updateChatMetadata],
  );

  const handleSelectServer = useCallback(
    (serverId: string) => {
      setActiveServer(serverId);
    },
    [setActiveServer],
  );

  const handleAddServer = useCallback(
    async (server: {
      name: string;
      baseUrl: string;
      apiKey?: string;
      transport: "SSE" | "STREAMABLE_HTTP";
    }) => {
      const trimmedUrl = server.baseUrl.trim();
      if (!trimmedUrl) {
        return;
      }
      const name = server.name.trim() || `Server ${servers.length + 1}`;
      const apiKey = server.apiKey?.trim() || undefined;
      
      let serverId: string | undefined;
      
      try {
        serverId = await registerServer({
          name,
          baseUrl: trimmedUrl,
          apiKey,
          transport: server.transport,
        });
        setActiveServer(serverId);
        void setServerStatus(serverId, "idle");
      } catch (error) {
        console.error("Failed to register MCP server", error);
        const errorMessage = error instanceof Error ? error.message : "Unknown error";
        // Show error to user
        if (serverId) {
          void setServerStatus(serverId, "error");
        }
        alert(`Failed to register MCP server '${name}': ${errorMessage}`);
      }
    },
    [registerServer, servers.length, setActiveServer, setServerStatus],
  );

  const handleRemoveServer = useCallback(
    async (serverId: string) => {
      void disconnectMcpSession(serverId);
      await removeServer(serverId);
    },
    [removeServer],
  );

  const handleDeleteChat = useCallback(
    async (chatId: string) => {
      await deleteChat(chatId);
      if (currentChatId === chatId) {
        navigate("/");
      }
    },
    [currentChatId, deleteChat, navigate],
  );

  const headerSubtitle = useMemo(
    () =>
      "Chat with OpenAI-compatible servers via the Responses API. Adjust settings or explore your automations.",
    [],
  );

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1>Chatbot Console</h1>
          <p className="app-subtitle">{headerSubtitle}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="secondary"
            onClick={() => navigate("/n8n")}
            title="Manage your n8n workflows"
          >
            Automations
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setIsSettingsOpen(true)}
            title="Adjust application settings"
          >
            Show settings
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setIsShortcutsOpen(true)}
            title="View keyboard shortcuts"
          >
            Keyboard shortcuts
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => void handleNewChat()}
            title={`Shortcut: ${newChatShortcutHint}`}
          >
            {`New chat (${newChatShortcutHint})`}
          </button>
        </div>
      </header>

      {isSettingsOpen && (
        <Modal title="Settings" onClose={() => setIsSettingsOpen(false)}>
          <SettingsPanel
            config={config}
            availableModels={availableModels}
            servers={servers}
            activeServerId={activeServerId}
            isSyncingServers={isSyncingServers}
            onModelChange={setModel}
            onTitleModelChange={setTitleModel}
            onRefreshModels={handleRefreshModels}
            onSelectServer={handleSelectServer}
            onAddServer={handleAddServer}
            onRemoveServer={handleRemoveServer}
          />
          {activeServer && activeServer.status === "connected" && (
            <McpCapabilitiesPanel
              capabilities={activeServer.capabilities ?? null}
              isLoading={isLoadingCapabilities}
              serverName={activeServer.name}
            />
          )}
        </Modal>
      )}

      {isShortcutsOpen && (
        <Modal title="Keyboard shortcuts" onClose={() => setIsShortcutsOpen(false)}>
          <ul className="shortcut-list">
            {shortcutItems.map((item) => (
              <li key={item.description} className="shortcut-item">
                <div className="shortcut-keys">
                  <kbd data-active={isApplePlatform || item.mac === item.other ? "true" : "false"}>
                    {item.mac}
                  </kbd>
                  {item.mac !== item.other && (
                    <>
                      <span className="shortcut-separator" aria-hidden="true">
                        /
                      </span>
                      <kbd data-active={!isApplePlatform || item.mac === item.other ? "true" : "false"}>
                        {item.other}
                      </kbd>
                    </>
                  )}
                </div>
                <p className="shortcut-description">{item.description}</p>
              </li>
            ))}
          </ul>
        </Modal>
      )}

      <div className="chat-shell">
        <ChatSidebar
          chats={chatSummaries}
          activeChatId={currentChatId}
          isSyncing={isSyncingChats}
          onRefresh={() => void refreshChats()}
          onNewChat={() => void handleNewChat()}
          onDelete={(chatId) => void handleDeleteChat(chatId)}
          onRename={(chatId, title) => void handleRenameChat(chatId, title)}
          newChatShortcutHint={newChatShortcutHint}
        />
        <main className="panel chat-panel">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<ChatLayout />}>
        <Route index element={<ChatRoute />} />
        <Route path="chat/:chatId" element={<ChatRoute />} />
        <Route path="n8n" element={<N8nPanel />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
