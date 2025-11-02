import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
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
  const [expandedToolCalls, setExpandedToolCalls] = useState<Set<string>>(new Set())
  const [liveRegionMessage, setLiveRegionMessage] = useState<string>('')

  const toggleToolCalls = (messageId: string) => {
    setExpandedToolCalls(prev => {
      const newSet = new Set(prev)
      if (newSet.has(messageId)) {
        newSet.delete(messageId)
      } else {
        newSet.add(messageId)
      }
      return newSet
    })
  }

  useEffect(() => {
    if (messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' })
    }
    
    // Announce new messages to screen readers
    if (messages.length > 0) {
      const lastMessage = messages[messages.length - 1]
      if (lastMessage.role === 'assistant') {
        setLiveRegionMessage(`Assistant: ${lastMessage.content.substring(0, 100)}${lastMessage.content.length > 100 ? '...' : ''}`)
      }
    }
  }, [messages.length, isLoading, messages])

  useEffect(() => {
    if (!copyError) {
      return undefined
    }

    const timeout = globalThis.setTimeout(() => setCopyError(null), 2000)
    return () => globalThis.clearTimeout(timeout)
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
      globalThis.setTimeout(() => setCopiedMessageId(null), 1500)
    } catch (error) {
      console.error('Failed to copy message', error)
      setCopyError('Copy failed')
      setLiveRegionMessage('Failed to copy message to clipboard')
    }
  }

  return (
    <div className="chat-messages">
      {/* Screen reader announcements */}
      <div 
        role="status" 
        aria-live="polite" 
        aria-atomic="true"
        className="sr-only"
      >
        {liveRegionMessage}
      </div>
      
      {/* Loading status announcement */}
      {isLoading && (
        <div 
          role="status" 
          aria-live="polite" 
          aria-atomic="true"
          className="sr-only"
        >
          Assistant is thinking...
        </div>
      )}
      
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
          
          {/* Tool Call Badge */}
          {message.toolCalls && message.toolCalls.length > 0 && (
            <button
              type="button"
              className="tool-call-badge"
              onClick={() => toggleToolCalls(message.id)}
              aria-expanded={expandedToolCalls.has(message.id)}
              aria-label={`${expandedToolCalls.has(message.id) ? 'Hide' : 'Show'} ${message.toolCalls.length} tool execution${message.toolCalls.length > 1 ? 's' : ''}`}
            >
              <span className="tool-icon" aria-hidden="true">🔧</span>
              <span className="tool-count">
                Executed {message.toolCalls.length} tool{message.toolCalls.length > 1 ? 's' : ''}
              </span>
              <span className="expand-icon" aria-hidden="true">{expandedToolCalls.has(message.id) ? '▼' : '▶'}</span>
            </button>
          )}
          
          {/* Expandable Tool Call Details */}
          {message.toolCalls && expandedToolCalls.has(message.id) && (
            <div className="tool-call-details" role="region" aria-label="Tool execution details">
              {message.toolCalls.map((tool) => (
                <div key={`${message.id}-${tool.toolName}-${tool.server}`} className={`tool-call-item ${tool.success ? 'success' : 'error'}`}>
                  <div className="tool-call-header">
                    <span className="tool-name">{tool.toolName}</span>
                    <span className="tool-status" aria-label={tool.success ? 'Success' : 'Error'}>
                      {tool.success ? '✓' : '✗'}
                    </span>
                  </div>
                  <div className="tool-call-server">Server: {tool.server}</div>
                  <details className="tool-call-args">
                    <summary>Arguments</summary>
                    <pre><code>{tool.arguments}</code></pre>
                  </details>
                  <details className="tool-call-result">
                    <summary>Result</summary>
                    <pre><code>{tool.result}</code></pre>
                  </details>
                </div>
              ))}
            </div>
          )}
          
          <div className="chat-bubble">
            <ReactMarkdown>{message.content}</ReactMarkdown>
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
