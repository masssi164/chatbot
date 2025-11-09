import type { StatusUpdate } from "../store/streamingStore";

interface ResponseStatusPanelProps {
  updates: StatusUpdate[];
  isStreaming: boolean;
}

const formatter = new Intl.DateTimeFormat(undefined, {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

function formatTimestamp(timestamp: number) {
  try {
    return formatter.format(timestamp);
  } catch {
    return new Date(timestamp).toLocaleTimeString();
  }
}

export function ResponseStatusPanel({ updates, isStreaming }: ResponseStatusPanelProps) {
  const latest = updates.at(-1);
  const announcement = latest
    ? `${latest.label}${latest.detail ? `: ${latest.detail}` : ""}`
    : isStreaming
      ? "Assistant is preparing a response"
      : "No status updates yet";

  return (
    <section className="status-updates" aria-live="polite" aria-atomic="false">
      <header className="status-updates__header">
        <div>
          <h2>Response Status</h2>
          <p className="status-updates__sub">Tracks lifecycle events from the streaming API.</p>
        </div>
        {isStreaming && <span className="status-updates__pill">Live</span>}
      </header>

      {updates.length === 0 ? (
        <p className="status-updates__empty">Status updates will appear here once a response starts.</p>
      ) : (
        <ol className="status-updates__list">
          {updates.map((update) => (
            <li key={update.id} className={`status-update status-update--${update.severity}`}>
              <div className="status-update__body">
                <span className="status-update__label">{update.label}</span>
                {update.detail && <span className="status-update__detail">{update.detail}</span>}
              </div>
              <time className="status-update__time" dateTime={new Date(update.timestamp).toISOString()}>
                {formatTimestamp(update.timestamp)}
              </time>
            </li>
          ))}
        </ol>
      )}

      <div className="sr-only" role="status" aria-live="assertive">
        {announcement}
      </div>
    </section>
  );
}
