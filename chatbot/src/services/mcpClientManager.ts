import { Client } from '@modelcontextprotocol/sdk/client'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

interface SessionEntry {
  client: Client
  transport: StreamableHTTPClientTransport
  endpoint: string
}

const sessions = new Map<string, SessionEntry>()

function deriveMcpEndpoint(baseUrl: string) {
  const trimmed = baseUrl.trim().replace(/\/+$/, '')
  if (!trimmed) {
    return ''
  }

  if (/\/mcp$/i.test(trimmed)) {
    return trimmed
  }

  if (/\/v\d+$/i.test(trimmed)) {
    return `${trimmed.replace(/\/v\d+$/i, '')}/mcp`
  }

  return `${trimmed}/mcp`
}

function toUrl(value: string) {
  try {
    return new URL(value)
  } catch (error) {
    const base = typeof window !== 'undefined' ? window.location.origin : 'http://localhost'
    return new URL(value, base)
  }
}

export async function ensureMcpSession(options: { serverId: string; baseUrl: string; apiKey?: string }) {
  const endpoint = deriveMcpEndpoint(options.baseUrl)
  if (!endpoint) {
    await disconnectMcpSession(options.serverId)
    return false
  }

  const existing = sessions.get(options.serverId)
  if (existing && existing.endpoint === endpoint) {
    return true
  }

  if (existing) {
    await disconnectMcpSession(options.serverId)
  }

  try {
    const headers: Record<string, string> = {}
    if (options.apiKey) {
      headers.Authorization = `Bearer ${options.apiKey}`
    }

    const transport = new StreamableHTTPClientTransport(toUrl(endpoint), {
      requestInit: Object.keys(headers).length ? { headers } : undefined,
    })

    const client = new Client({
      name: 'openai-chat-console',
      version: '1.0.0',
      title: 'OpenAI Chat Console',
    })

    await client.connect(transport)
    sessions.set(options.serverId, { client, transport, endpoint })
    return true
  } catch (error) {
    console.error(`Failed to initialize MCP session for ${options.serverId}`, error)
    await disconnectMcpSession(options.serverId)
    return false
  }
}

export async function disconnectMcpSession(serverId: string) {
  const entry = sessions.get(serverId)
  if (!entry) {
    return
  }

  try {
    await entry.client.close()
  } catch (error) {
    console.warn(`Failed to close MCP session for ${serverId}`, error)
  } finally {
    sessions.delete(serverId)
  }
}
