import { create } from 'zustand'

export type McpServerStatus = 'idle' | 'connecting' | 'connected' | 'error'

export interface McpServer {
  id: string
  name: string
  baseUrl: string
  apiKey?: string
  status: McpServerStatus
  lastUpdated: number
}

interface McpServerState {
  servers: McpServer[]
  activeServerId: string | null
  registerServer: (server: Omit<McpServer, 'id' | 'status' | 'lastUpdated'> & { id?: string }) => string
  setActiveServer: (serverId: string) => void
  updateServer: (serverId: string, updates: Partial<Omit<McpServer, 'id'>>) => void
  setServerStatus: (serverId: string, status: McpServerStatus) => void
  removeServer: (serverId: string) => void
}

function safeId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  return Math.random().toString(36).slice(2)
}

export const useMcpServerStore = create<McpServerState>((set) => ({
  servers: [],
  activeServerId: null,
  registerServer: (server) => {
    const normalizedBaseUrl = server.baseUrl.trim()
    const normalizedApiKey = server.apiKey?.trim() || undefined

    if (!normalizedBaseUrl) {
      throw new Error('Server URL cannot be empty')
    }

    const id = server.id ?? safeId()
    set((state) => {
      const existing = state.servers.find((item) => item.id === id)
      if (existing) {
        return {
          servers: state.servers.map((item) =>
            item.id === id
              ? {
                  ...item,
                  ...server,
                  baseUrl: normalizedBaseUrl,
                  apiKey: normalizedApiKey,
                  lastUpdated: Date.now(),
                }
              : item,
          ),
        }
      }

      return {
        servers: [
          ...state.servers,
          {
            id,
            name: server.name,
            baseUrl: normalizedBaseUrl,
            apiKey: normalizedApiKey,
            status: 'idle',
            lastUpdated: Date.now(),
          },
        ],
        activeServerId: state.activeServerId ?? id,
      }
    })
    return id
  },
  setActiveServer: (serverId) =>
    set((state) => ({
      activeServerId: state.servers.some((server) => server.id === serverId)
        ? serverId
        : state.activeServerId,
    })),
  updateServer: (serverId, updates) =>
    set((state) => {
      let changed = false
      const servers = state.servers.map((server) => {
        if (server.id !== serverId) {
          return server
        }

        const nextBaseUrl = updates.baseUrl !== undefined ? updates.baseUrl.trim() : server.baseUrl
        const nextApiKey = updates.apiKey !== undefined ? updates.apiKey?.trim() || undefined : server.apiKey

        if (
          nextBaseUrl === server.baseUrl &&
          nextApiKey === server.apiKey &&
          (updates.name === undefined || updates.name === server.name)
        ) {
          return server
        }

        changed = true
        return {
          ...server,
          ...updates,
          baseUrl: nextBaseUrl,
          apiKey: nextApiKey,
          lastUpdated: Date.now(),
        }
      })

      if (!changed) {
        return state
      }

      return { servers }
    }),
  setServerStatus: (serverId, status) =>
    set((state) => ({
      servers: state.servers.map((server) =>
        server.id === serverId
          ? {
              ...server,
              status,
              lastUpdated: Date.now(),
            }
          : server,
      ),
    })),
  removeServer: (serverId) =>
    set((state) => {
      const remaining = state.servers.filter((server) => server.id !== serverId)
      return {
        servers: remaining,
        activeServerId:
          state.activeServerId === serverId ? remaining[0]?.id ?? null : state.activeServerId,
      }
    }),
}))
