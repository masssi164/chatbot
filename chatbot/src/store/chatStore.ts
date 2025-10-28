import OpenAI from 'openai'
import { create } from 'zustand'
import type {
  EasyInputMessage,
  Response,
  ResponseCreateParamsNonStreaming,
} from 'openai/resources/responses/responses.mjs'

export type Role = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: Role
  content: string
  createdAt: number
}

export interface ChatConfig {
  baseUrl: string
  apiKey?: string
  model: string
  systemPrompt: string
}

interface ChatState {
  config: ChatConfig
  messages: ChatMessage[]
  availableModels: string[]
  isLoading: boolean
  error?: string
  setBaseUrl: (baseUrl: string) => void
  setApiKey: (apiKey: string) => void
  setModel: (model: string) => void
  setSystemPrompt: (prompt: string) => void
  sendMessage: (content: string) => Promise<void>
  fetchModels: () => Promise<boolean>
  resetModels: () => void
  resetConversation: () => void
}

interface PersistedConfig {
  baseUrl: string
  apiKey?: string
  model: string
  systemPrompt: string
}

const STORAGE_KEY = 'chat.console.config.v1'

export const defaultModels = ['gpt-4.1-mini', 'gpt-4.1', 'o4-mini', 'o4']
export const defaultSystemPrompt = 'You are a helpful AI assistant.'
export const DEFAULT_BASE_URL = 'http://localhost:1234/v1'

function safeId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  return Math.random().toString(36).slice(2)
}

function normalizeBaseUrl(rawUrl: string) {
  const trimmed = rawUrl.trim()
  if (!trimmed) {
    return deriveAbsoluteUrl('/v1')
  }

  const withoutTrailingSlash = trimmed.replace(/\/+$/, '')

  if (/^https?:\/\//i.test(withoutTrailingSlash)) {
    return ensureApiSuffix(withoutTrailingSlash)
  }

  if (typeof window !== 'undefined') {
    const leadingSlash = withoutTrailingSlash.startsWith('/')
      ? withoutTrailingSlash
      : `/${withoutTrailingSlash}`
    return ensureApiSuffix(`${window.location.origin}${leadingSlash}`)
  }

  return ensureApiSuffix(withoutTrailingSlash)
}

function deriveAbsoluteUrl(path: string) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  if (typeof window !== 'undefined') {
    return `${window.location.origin}${normalizedPath}`
  }
  return normalizedPath
}

function ensureApiSuffix(url: string) {
  const cleaned = url.replace(/\/+$/, '')
  if (/\/v\d+$/i.test(cleaned)) {
    return cleaned
  }
  return `${cleaned}/v1`
}

function buildBaseUrlCandidates(rawBaseUrl: string) {
  const candidates = new Set<string>()
  const trimmedRaw = rawBaseUrl.trim()

  if (typeof window !== 'undefined') {
    if (!trimmedRaw || trimmedRaw === DEFAULT_BASE_URL) {
      candidates.add(deriveAbsoluteUrl('/v1'))
    }
  }

  candidates.add(normalizeBaseUrl(rawBaseUrl))

  return Array.from(candidates)
}

function buildInputMessages(messages: ChatMessage[], systemPrompt: string): EasyInputMessage[] {
  const content: EasyInputMessage[] = []

  if (systemPrompt.trim().length > 0) {
    content.push({
      role: 'system',
      content: systemPrompt,
    })
  }

  for (const message of messages) {
    content.push({
      role: message.role,
      content: message.content,
    })
  }

  return content
}

type ResponseOutput = Response['output']

function isAssistantOutput(
  item: NonNullable<ResponseOutput>[number],
): item is Extract<NonNullable<ResponseOutput>[number], { role: 'assistant' }> {
  return typeof item === 'object' && item !== null && 'role' in item && item.role === 'assistant'
}

function extractAssistantText(payload: Response) {
  if (payload.output_text) {
    return payload.output_text.trim()
  }

  if (!payload.output) {
    return ''
  }

  const chunks = payload.output
    .filter((item) => isAssistantOutput(item))
    .flatMap((item) => {
      if ('content' in item && Array.isArray(item.content)) {
        return item.content as Array<{ type: string; text?: string }>
      }

      return []
    })
    .map((entry) => {
      if (entry.type === 'output_text' || entry.type === 'text' || entry.type === 'input_text') {
        return entry.text?.trim() ?? ''
      }

      return ''
    })
    .filter((part) => part.length > 0)

  return chunks.join('\n\n').trim()
}

function readPersistedConfig(): PersistedConfig | null {
  if (typeof window === 'undefined') {
    return null
  }

  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }

    return JSON.parse(raw) as PersistedConfig
  } catch {
    return null
  }
}

function sanitizePersistedConfig(config: PersistedConfig | null): PersistedConfig | null {
  if (!config) {
    return null
  }

  const trimmedBaseUrl = config.baseUrl?.trim() ?? ''
  const trimmedApiKey = config.apiKey?.trim()
  const trimmedModel = config.model?.trim() ?? ''

  return {
    baseUrl: trimmedBaseUrl,
    ...(trimmedApiKey ? { apiKey: trimmedApiKey } : {}),
    model: trimmedModel,
    systemPrompt: config.systemPrompt ?? defaultSystemPrompt,
  }
}

function persistConfig(config: ChatConfig) {
  if (typeof window === 'undefined') {
    return
  }

  const payload: PersistedConfig = {
    baseUrl: config.baseUrl.trim(),
    ...(config.apiKey?.trim() ? { apiKey: config.apiKey.trim() } : {}),
    model: config.model,
    systemPrompt: config.systemPrompt,
  }

  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
}

const persistedConfig = sanitizePersistedConfig(readPersistedConfig())

export const initialConfig: ChatConfig = {
  baseUrl: persistedConfig?.baseUrl?.length ? persistedConfig.baseUrl : DEFAULT_BASE_URL,
  apiKey: persistedConfig?.apiKey ?? import.meta.env.VITE_OPENAI_API_KEY,
  model: persistedConfig?.model ?? '',
  systemPrompt: persistedConfig?.systemPrompt ?? defaultSystemPrompt,
}

export const useChatStore = create<ChatState>((set, get) => ({
  config: initialConfig,
  messages: [],
  availableModels: [],
  isLoading: false,
  error: undefined,
  setBaseUrl: (baseUrl) =>
    set((state) => {
      const trimmed = baseUrl.trim()
      if (trimmed === state.config.baseUrl) {
        if (!trimmed && state.availableModels.length > 0) {
          const nextConfig: ChatConfig = { ...state.config, model: '' }
          persistConfig(nextConfig)
          return {
            config: nextConfig,
            availableModels: [],
          }
        }
        return {}
      }

      const nextConfig: ChatConfig = {
        ...state.config,
        baseUrl: trimmed,
        ...(trimmed ? {} : { model: '' }),
      }
      persistConfig(nextConfig)
      return {
        config: nextConfig,
        ...(trimmed ? {} : { availableModels: [] }),
      }
    }),
  setApiKey: (apiKey) =>
    set((state) => {
      const value = apiKey || undefined
      if (value === state.config.apiKey) {
        return {}
      }
      const nextConfig: ChatConfig = { ...state.config, apiKey: value }
      persistConfig(nextConfig)
      return { config: nextConfig }
    }),
  setModel: (model) =>
    set((state) => {
      const trimmed = model.trim()
      if (trimmed === state.config.model) {
        return {}
      }
      const nextConfig: ChatConfig = { ...state.config, model: trimmed }
      persistConfig(nextConfig)
      return { config: nextConfig }
    }),
  setSystemPrompt: (prompt) =>
    set((state) => {
      if (prompt === state.config.systemPrompt) {
        return {}
      }
      const nextConfig: ChatConfig = { ...state.config, systemPrompt: prompt }
      persistConfig(nextConfig)
      return { config: nextConfig }
    }),
  sendMessage: async (content: string) => {
    const trimmed = content.trim()

    if (!trimmed) {
      return
    }

    const state = get()
    if (!state.config.model.trim()) {
      set({
        error: 'Select a model before sending a message.',
      })
      return
    }

    const userMessage: ChatMessage = {
      id: safeId(),
      role: 'user',
      content: trimmed,
      createdAt: Date.now(),
    }

    const pendingMessages = [...state.messages, userMessage]

    set({
      messages: pendingMessages,
      isLoading: true,
      error: undefined,
    })

    try {
      const params: ResponseCreateParamsNonStreaming = {
        model: state.config.model,
        input: buildInputMessages(pendingMessages, state.config.systemPrompt),
        stream: false,
      }

      const baseUrlCandidates = buildBaseUrlCandidates(state.config.baseUrl)
      let response: Response | null = null
      let lastError: unknown = null

      for (const candidate of baseUrlCandidates) {
        try {
          const client = new OpenAI({
            apiKey: state.config.apiKey ?? '',
            baseURL: candidate,
            dangerouslyAllowBrowser: true,
          })

          response = await client.responses.create(params)
          break
        } catch (error) {
          lastError = error
        }
      }

      if (!response) {
        throw lastError instanceof Error
          ? lastError
          : new Error('Failed to send message. Check server connectivity or CORS configuration.')
      }

      const text = extractAssistantText(response)

      if (!text) {
        throw new Error('No response text returned from the model.')
      }

      const assistantMessage: ChatMessage = {
        id: response.id ?? safeId(),
        role: 'assistant',
        content: text,
        createdAt: Date.now(),
      }

      set((current) => ({
        messages: [...current.messages, assistantMessage],
        isLoading: false,
      }))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error'

      set({
        isLoading: false,
        error: message,
      })
    }
  },
  fetchModels: async () => {
    const state = get()
    const baseUrlCandidates = buildBaseUrlCandidates(state.config.baseUrl)
    let lastError: unknown = null

    for (const candidate of baseUrlCandidates) {
      try {
        const client = new OpenAI({
          apiKey: state.config.apiKey ?? '',
          baseURL: candidate,
          dangerouslyAllowBrowser: true,
        })

        const response = await client.models.list()
        const models = response.data
          .map((item) => item.id)
          .filter((modelId): modelId is string => Boolean(modelId))

        if (!models.length) {
          throw new Error('No models returned by the server.')
        }

        const preferredModel =
          state.config.model && models.includes(state.config.model)
            ? state.config.model
            : models[0]
        const nextConfig: ChatConfig = { ...state.config, model: preferredModel }
        persistConfig(nextConfig)

        set({
          availableModels: models,
          config: nextConfig,
        })

        return true
      } catch (error) {
        lastError = error
      }
    }

    console.error('Failed to fetch models', lastError)

    set((current) => {
      const nextConfig: ChatConfig = { ...current.config, model: '' }
      persistConfig(nextConfig)
      return {
        availableModels: [],
        config: nextConfig,
      }
    })

    return false
  },
  resetModels: () =>
    set((state) => {
      const nextConfig: ChatConfig = { ...state.config, model: '' }
      persistConfig(nextConfig)
      return {
        availableModels: [],
        config: nextConfig,
      }
    }),
  resetConversation: () =>
    set({
      messages: [],
      error: undefined,
    }),
}))
