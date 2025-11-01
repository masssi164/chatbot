import { Link, useLocation } from "react-router-dom";
import type { ChatSummary } from "../store/chatStore";
import { formatTimestamp } from "../utils/format";

interface ChatSidebarProps {
  chats: ChatSummary[];
  activeChatId: string | null;
  isSyncing: boolean;
  onRefresh: () => void;
  onNewChat: () => void;
  onDelete: (chatId: string) => void;
  onRename: (chatId: string, title: string | null) => void;
  newChatShortcutHint: string;
}

function ChatSidebar({
  chats,
  activeChatId,
  isSyncing,
  onRefresh,
  onNewChat,
  onDelete,
  onRename,
  newChatShortcutHint,
}: ChatSidebarProps) {
  const location = useLocation();

  const currentPath = location.pathname;

  return (
    <aside className="panel chat-sidebar">
      <div className="chat-sidebar-header">
        <div>
          <h2>Saved chats</h2>
          <p>{isSyncing ? "Syncing…" : `${chats.length} total`}</p>
        </div>
        <div className="chat-sidebar-actions">
          <button
            type="button"
            className="secondary"
            onClick={onRefresh}
            disabled={isSyncing}
          >
            Refresh
          </button>
          <button
            type="button"
            onClick={onNewChat}
            title={`Shortcut: ${newChatShortcutHint}`}
          >
            {`New chat (${newChatShortcutHint})`}
          </button>
        </div>
      </div>

      {chats.length === 0 ? (
        <div className="chat-sidebar-empty">
          <p>No stored conversations yet.</p>
          <button
            type="button"
            onClick={onNewChat}
            title={`Shortcut: ${newChatShortcutHint}`}
          >
            Start your first chat
          </button>
        </div>
      ) : (
        <ul className="chat-sidebar-list">
          {chats.map((chat) => {
            const href = `/chat/${chat.chatId}`;
            const isActive =
              activeChatId === chat.chatId ||
              currentPath === href ||
              currentPath.endsWith(`${chat.chatId}/`);
            return (
              <li key={chat.chatId} className={isActive ? "active" : ""}>
                <div className="chat-sidebar-row">
                  <Link to={href} className="chat-sidebar-link">
                    <div>
                      <p className="chat-sidebar-title">
                        {chat.title || "Untitled chat"}
                      </p>
                      <span className="chat-sidebar-meta">
                        {formatTimestamp(chat.updatedAt || chat.createdAt)} ·{" "}
                        {chat.messageCount} messages
                      </span>
                    </div>
                  </Link>
                  <details className="chat-sidebar-menu">
                    <summary
                      className="chat-sidebar-menu-button"
                      aria-label={`Options for ${chat.title || "Untitled chat"}`}
                      onClick={(event) => event.stopPropagation()}
                    >
                      ⋮
                    </summary>
                    <div className="chat-sidebar-menu-content">
                      <button
                        type="button"
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          const next = window.prompt(
                            "Neuen Chat-Titel eingeben",
                            chat.title ?? "",
                          );
                          (event.currentTarget.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open");
                          if (next === null) {
                            return;
                          }
                          const trimmed = next.trim();
                          onRename(chat.chatId, trimmed.length ? trimmed : null);
                        }}
                      >
                        Rename
                      </button>
                      <button
                        type="button"
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          (event.currentTarget.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open");
                          onDelete(chat.chatId);
                        }}
                      >
                        Delete
                      </button>
                    </div>
                  </details>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </aside>
  );
}

export default ChatSidebar;
