import { type FormEvent, useEffect, useRef, useState } from "react";
import "./App.css";
import { useChatStore } from "./store/chatStore";

function App() {
  // Separate selectors to avoid re-creating objects on every render
  const ensureConversation = useChatStore((state) => state.ensureConversation);
  const messages = useChatStore((state) => state.messages);
  const toolCalls = useChatStore((state) => state.toolCalls);
  const conversationTitle = useChatStore((state) => state.conversationTitle);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const streamError = useChatStore((state) => state.streamError);
  const sendMessage = useChatStore((state) => state.sendMessage);
  const abortStreaming = useChatStore((state) => state.abortStreaming);

  const [prompt, setPrompt] = useState("");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    void ensureConversation();
  }, [ensureConversation]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = prompt.trim();
    if (!trimmed || isStreaming) {
      return;
    }
    setSubmitError(null);
    try {
      setPrompt("");
      await sendMessage(trimmed);
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : String(error));
    }
  };

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1>Chat Console</h1>
          {conversationTitle && <p className="conversation-title">{conversationTitle}</p>}
        </div>
        <div className="status-bar">
          {isStreaming ? <span className="status streaming">Streaming…</span> : <span className="status idle">Idle</span>}
          {streamError && <span className="status error">{streamError}</span>}
          {submitError && <span className="status error">{submitError}</span>}
        </div>
      </header>

      <main className="chat-main">
        <section className="chat-transcript" aria-live="polite">
          {messages.map((message) => (
            <article key={message.id} className={`message message-${message.role}`}>
              <header className="message-meta">
                <span className="message-role">{message.role}</span>
                <time dateTime={new Date(message.createdAt).toISOString()}>{new Date(message.createdAt).toLocaleTimeString()}</time>
              </header>
              <p className="message-content">
                {message.content || <span className="message-placeholder">…</span>}
                {message.streaming && <span className="cursor">▍</span>}
              </p>
            </article>
          ))}
          <div ref={endRef} />
        </section>

        <aside className="tool-panel">
          <h2>Tool Calls</h2>
          {toolCalls.length === 0 ? (
            <p className="tool-empty">No tool activity yet.</p>
          ) : (
            <ul className="tool-list">
              {toolCalls.map((tool) => (
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

      <form className="chat-input" onSubmit={handleSubmit}>
        <label htmlFor="prompt" className="sr-only">
          Prompt
        </label>
        <textarea
          id="prompt"
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="Ask something…"
          rows={3}
          disabled={isStreaming}
        />
        <div className="input-actions">
          <button type="submit" disabled={isStreaming || !prompt.trim()}>
            Send
          </button>
          <button type="button" className="secondary" disabled={!isStreaming} onClick={() => abortStreaming()}>
            Stop
          </button>
        </div>
      </form>
    </div>
  );
}

export default App;
