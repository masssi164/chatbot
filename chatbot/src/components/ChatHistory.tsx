import { useEffect, useRef, useState } from 'react'
import type { ChatMessage } from '../store/chatStore'
import { formatTimestamp } from '../utils/format'

interface ChatHistoryProps {
  messages: ChatMessage[]
  isLoading: boolean
}

export function ChatHistory({ messages, isLoading }: ChatHistoryProps) {
  const messagesEndRef = useRef<HTMLDivElement | null>(null)
  const [copiedMessageId, setCopiedMessageId] = useState<string | null>(null)
  const [copyError, setCopyError] = useState<string | null>(null)

  useEffect(() => {
    if (messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages.length, isLoading])

  useEffect(() => {
    if (!copyError) {
      return undefined
    }

    const timeout = window.setTimeout(() => setCopyError(null), 2000)
    return () => window.clearTimeout(timeout)
  }, [copyError])

  const handleCopy = async (message: ChatMessage) => {
    try {
      if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
        await navigator.clipboard.writeText(message.content)
      } else {
        const textarea = document.createElement('textarea')
        textarea.value = message.content
        textarea.style.position = 'fixed'
        textarea.style.opacity = '0'
        document.body.appendChild(textarea)
        textarea.focus()
        textarea.select()
        document.execCommand('copy')
        document.body.removeChild(textarea)
      }
      setCopiedMessageId(message.id)
      setCopyError(null)
      window.setTimeout(() => setCopiedMessageId(null), 1500)
    } catch (error) {
      console.error('Failed to copy message', error)
      setCopyError('Copy failed')
    }
  }

  return (
    <div className="chat-messages">
      {messages.length === 0 && !isLoading && (
        <div className="empty-state">
          <p>Start chatting to see responses.</p>
        </div>
      )}
      {messages.map((message) => (
        <div
          key={message.id}
          className={`chat-message ${message.role === 'user' ? 'user' : 'assistant'}`}
        >
          <div className="chat-message-meta">
            <span className="chat-role">{message.role === 'user' ? 'You' : 'Assistant'}</span>
            <time className="chat-time">{formatTimestamp(message.createdAt)}</time>
          </div>
          <div className="chat-bubble">
            <p>{message.content}</p>
          </div>
          <div className="chat-message-actions">
            <button type="button" className="copy-button" onClick={() => handleCopy(message)}>
              Copy
            </button>
            {copiedMessageId === message.id && <span className="copy-feedback">Copied!</span>}
          </div>
        </div>
      ))}
      {isLoading && (
        <div className="chat-message assistant">
          <div className="chat-message-meta">
            <span className="chat-role">Assistant</span>
            <span className="typing-indicator">
              <span />
              <span />
              <span />
            </span>
          </div>
          <div className="chat-bubble">
            <p>Thinking…</p>
          </div>
        </div>
      )}
      <div ref={messagesEndRef} />
      {copyError && <p className="error-message copy-error">{copyError}</p>}
    </div>
  )
}

export default ChatHistory
