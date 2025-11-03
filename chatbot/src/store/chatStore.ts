import { create } from "zustand";
import {
  ApiError,
  apiClient,
  type BackendChat,
  type BackendChatMessage,
  type BackendCreateChatRequest,
  type BackendToolCallInfo,
  type BackendUpdateChatRequest,
} from "../services/apiClient";
import logger from "../utils/logger";

export type Role = "user" | "assistant";

export interface ToolCallInfo {
  toolName: string;
  server: string;
  arguments: string;
  result: string;
  success: boolean;
}

export interface ChatMessage {
  id: string;
  role: Role;
  content: string;
  createdAt: number;
  toolCalls?: ToolCallInfo[];
}

export interface ChatSummary {
  chatId: string;
  title?: string | null;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
}

export interface ChatConfig {
  model: string;
  systemPrompt: string;
  titleModel?: string;
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
}

interface ChatState {
  config: ChatConfig;
  messages: ChatMessage[];
  availableModels: string[];
  chatSummaries: ChatSummary[];
  currentChatId: string | null;
  currentSystemPrompt: string;
  currentTitleModel: string | null;
  currentSystemPromptPersisted: string;
  currentTitleModelPersisted: string | null;
  hasUnsavedSystemPrompt: boolean;
  hasUnsavedTitleModel: boolean;
  isLoading: boolean;
  isSyncing: boolean;
  error?: string;
  createNewChat: () => Promise<string | null>;
  setModel: (model: string) => void;
  setTitleModel: (model: string) => void;
  setCurrentSystemPrompt: (prompt: string) => void;
  setCurrentTitleModel: (model: string | null) => void;
  saveCurrentChatMetadata: () => Promise<void>;
  setTemperature: (value: number | undefined) => void;
  setMaxTokens: (value: number | undefined) => void;
  setTopP: (value: number | undefined) => void;
  setPresencePenalty: (value: number | undefined) => void;
  setFrequencyPenalty: (value: number | undefined) => void;
  sendMessage: (content: string) => Promise<void>;
  fetchModels: () => Promise<boolean>;
  resetModels: () => void;
  resetConversation: () => void;
  refreshChats: () => Promise<void>;
  loadChat: (chatId: string) => Promise<boolean>;
  deleteChat: (chatId: string) => Promise<void>;
  updateChatMetadata: (
    chatId: string,
    metadata: BackendUpdateChatRequest,
  ) => Promise<void>;
}

interface PersistedConfig {
  model: string;
  systemPrompt: string;
  titleModel?: string;
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
}

const STORAGE_KEY = "chat.console.config.v2";

export const defaultSystemPrompt = "You are a helpful AI assistant.";
const DEFAULT_TEMPERATURE = 0.7;
const DEFAULT_MAX_TOKENS = 512;
const DEFAULT_TOP_P = 1;
const DEFAULT_PRESENCE_PENALTY = 0;
const DEFAULT_FREQUENCY_PENALTY = 0;

function safeId() {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID();
  }

  return Math.random().toString(36).slice(2);
}

function sanitizeNumber(value: unknown) {
  if (typeof value !== "number" || Number.isNaN(value)) {
    return undefined;
  }
  return value;
}

function readPersistedConfig(): PersistedConfig | null {
  if (typeof window === "undefined") {
    return null;
  }

  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }

    return JSON.parse(raw) as PersistedConfig;
  } catch {
    return null;
  }
}

function sanitizePersistedConfig(
  config: PersistedConfig | null,
): PersistedConfig | null {
  if (!config) {
    return null;
  }

  const trimmedModel = config.model?.trim() ?? "";
  const trimmedTitleModel = config.titleModel?.trim();

  return {
    model: trimmedModel,
    systemPrompt: config.systemPrompt ?? defaultSystemPrompt,
    ...(trimmedTitleModel ? { titleModel: trimmedTitleModel } : {}),
    ...(sanitizeNumber(config.temperature) !== undefined
      ? { temperature: sanitizeNumber(config.temperature) }
      : {}),
    ...(sanitizeNumber(config.maxTokens) !== undefined
      ? { maxTokens: sanitizeNumber(config.maxTokens) }
      : {}),
    ...(sanitizeNumber(config.topP) !== undefined
      ? { topP: sanitizeNumber(config.topP) }
      : {}),
    ...(sanitizeNumber(config.presencePenalty) !== undefined
      ? { presencePenalty: sanitizeNumber(config.presencePenalty) }
      : {}),
    ...(sanitizeNumber(config.frequencyPenalty) !== undefined
      ? { frequencyPenalty: sanitizeNumber(config.frequencyPenalty) }
      : {}),
  };
}

function persistConfig(config: ChatConfig) {
  if (typeof window === "undefined") {
    return;
  }

  const payload: PersistedConfig = {
    model: config.model,
    systemPrompt: config.systemPrompt,
    ...(config.titleModel?.trim()
      ? { titleModel: config.titleModel.trim() }
      : {}),
    ...(sanitizeNumber(config.temperature) !== undefined
      ? { temperature: sanitizeNumber(config.temperature) }
      : {}),
    ...(sanitizeNumber(config.maxTokens) !== undefined
      ? { maxTokens: sanitizeNumber(config.maxTokens) }
      : {}),
    ...(sanitizeNumber(config.topP) !== undefined
      ? { topP: sanitizeNumber(config.topP) }
      : {}),
    ...(sanitizeNumber(config.presencePenalty) !== undefined
      ? { presencePenalty: sanitizeNumber(config.presencePenalty) }
      : {}),
    ...(sanitizeNumber(config.frequencyPenalty) !== undefined
      ? { frequencyPenalty: sanitizeNumber(config.frequencyPenalty) }
      : {}),
  };

  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
}

const persistedConfig = sanitizePersistedConfig(readPersistedConfig());

export const initialConfig: ChatConfig = {
  model: persistedConfig?.model ?? "",
  systemPrompt: persistedConfig?.systemPrompt ?? defaultSystemPrompt,
  titleModel: persistedConfig?.titleModel ?? "",
  temperature: sanitizeNumber(persistedConfig?.temperature) ?? DEFAULT_TEMPERATURE,
  maxTokens: sanitizeNumber(persistedConfig?.maxTokens) ?? DEFAULT_MAX_TOKENS,
  topP: sanitizeNumber(persistedConfig?.topP) ?? DEFAULT_TOP_P,
  presencePenalty:
    sanitizeNumber(persistedConfig?.presencePenalty) ?? DEFAULT_PRESENCE_PENALTY,
  frequencyPenalty:
    sanitizeNumber(persistedConfig?.frequencyPenalty) ?? DEFAULT_FREQUENCY_PENALTY,
};


export const useChatStore = create<ChatState>((set, get) => {
  return {
    config: initialConfig,
    messages: [],
    availableModels: [],
    chatSummaries: [],
    currentChatId: null,
    currentSystemPrompt: initialConfig.systemPrompt,
    currentTitleModel: initialConfig.titleModel?.trim()?.length
      ? initialConfig.titleModel.trim()
      : null,
    currentSystemPromptPersisted: initialConfig.systemPrompt,
    currentTitleModelPersisted: initialConfig.titleModel?.trim()?.length
      ? initialConfig.titleModel.trim()
      : null,
    hasUnsavedSystemPrompt: false,
    hasUnsavedTitleModel: false,
    isLoading: false,
    isSyncing: false,
    error: undefined,
    createNewChat: async () => {
      const state = get();
      const model = state.config.model.trim();
      if (!model) {
        set({
          error: "Select a model before starting a chat.",
        });
        return null;
      }

      try {
        const completionParameters = buildCompletionParameters(state.config);
        const payload = {
          model,
          systemPrompt: state.config.systemPrompt,
          parameters: completionParameters,
          ...(state.config.titleModel?.trim()
            ? { titleModel: state.config.titleModel.trim() }
            : {}),
          messages: [],
        } satisfies BackendCreateChatRequest;

        const chat = await apiClient.createChat(payload);
        const summary = toSummary(chat);

        const nextSystemPrompt = chat.systemPrompt?.trim()?.length
          ? chat.systemPrompt.trim()
          : state.config.systemPrompt;
        const nextTitleModel = chat.titleModel?.trim()?.length
          ? chat.titleModel.trim()
          : state.config.titleModel?.trim()?.length
            ? state.config.titleModel.trim()
            : null;

        set((current) => {
          const summaries = [summary, ...current.chatSummaries.filter((item) => item.chatId !== summary.chatId)]
            .sort((a, b) => b.updatedAt - a.updatedAt);
          const persistedPrompt = nextSystemPrompt ?? state.config.systemPrompt;
          const persistedTitleModel = nextTitleModel ?? null;
          return {
            currentChatId: chat.chatId,
            messages: chat.messages.map(fromBackendMessage),
            chatSummaries: summaries,
            currentSystemPrompt: persistedPrompt,
            currentTitleModel: persistedTitleModel,
            currentSystemPromptPersisted: persistedPrompt,
            currentTitleModelPersisted: persistedTitleModel,
            hasUnsavedSystemPrompt: false,
            hasUnsavedTitleModel: false,
            isLoading: false,
            error: undefined,
          };
        });

        void get().refreshChats();

        return chat.chatId;
      } catch (error) {
        set({ error: getErrorMessage(error) });
        return null;
      }
    },
    setModel: (model) =>
      set((state) => {
        const trimmed = model.trim();
        if (trimmed === state.config.model) {
          return {};
        }
        const nextConfig: ChatConfig = {
          ...state.config,
          model: trimmed,
          ...(state.config.titleModel ? {} : { titleModel: trimmed }),
        };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    setTitleModel: (model) =>
      set((state) => {
        const trimmed = model.trim();
        const currentTitleModel = state.config.titleModel ?? "";
        if (trimmed === currentTitleModel) {
          return {};
        }
        const nextConfig: ChatConfig = {
          ...state.config,
          ...(trimmed ? { titleModel: trimmed } : { titleModel: undefined }),
        };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    setCurrentSystemPrompt: (prompt) =>
      set((state) => {
        const nextPrompt = prompt ?? "";
        const trimmedNext = nextPrompt.trim();
        const persistedTrimmed = (state.currentSystemPromptPersisted ?? "").trim();
        const hasChanged = trimmedNext !== persistedTrimmed;
        if (
          state.currentSystemPrompt === nextPrompt &&
          state.hasUnsavedSystemPrompt === hasChanged
        ) {
          return {};
        }
        return {
          currentSystemPrompt: nextPrompt,
          hasUnsavedSystemPrompt: hasChanged,
        };
      }),
    setCurrentTitleModel: (model) =>
      set((state) => {
        const normalized = model && model.trim().length ? model.trim() : null;
        const persisted = state.currentTitleModelPersisted;
        const hasChanged = normalized !== (persisted ?? null);
        if (
          state.currentTitleModel === normalized &&
          state.hasUnsavedTitleModel === hasChanged
        ) {
          return {};
        }
        return {
          currentTitleModel: normalized,
          hasUnsavedTitleModel: hasChanged,
        };
      }),
    setTemperature: (value) =>
      set((state) => {
        const normalized =
          value === undefined ? DEFAULT_TEMPERATURE : Math.max(0, Math.min(value, 2));
        if (state.config.temperature === normalized) {
          return {};
        }
        const nextConfig: ChatConfig = { ...state.config, temperature: normalized };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    setMaxTokens: (value) =>
      set((state) => {
        const normalized = value === undefined ? DEFAULT_MAX_TOKENS : Math.max(0, Math.floor(value));
        if (state.config.maxTokens === normalized) {
          return {};
        }
        const nextConfig: ChatConfig = { ...state.config, maxTokens: normalized };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    setTopP: (value) =>
      set((state) => {
        const normalized =
          value === undefined ? DEFAULT_TOP_P : Math.max(0, Math.min(value, 1));
        if (state.config.topP === normalized) {
          return {};
        }
        const nextConfig: ChatConfig = { ...state.config, topP: normalized };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    setPresencePenalty: (value) =>
      set((state) => {
        const normalized =
          value === undefined ? DEFAULT_PRESENCE_PENALTY : Math.max(-2, Math.min(value, 2));
        if (state.config.presencePenalty === normalized) {
          return {};
        }
        const nextConfig: ChatConfig = { ...state.config, presencePenalty: normalized };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    setFrequencyPenalty: (value) =>
      set((state) => {
        const normalized =
          value === undefined ? DEFAULT_FREQUENCY_PENALTY : Math.max(-2, Math.min(value, 2));
        if (state.config.frequencyPenalty === normalized) {
          return {};
        }
        const nextConfig: ChatConfig = { ...state.config, frequencyPenalty: normalized };
        persistConfig(nextConfig);
        return { config: nextConfig };
      }),
    sendMessage: async (content: string) => {
      const trimmed = content.trim();

      if (!trimmed) {
        return;
      }

      const state = get();
      if (!state.config.model.trim()) {
        set({
          error: "Select a model before sending a message.",
        });
        return;
      }

      logger.info("Sending user message", {
        chatId: state.currentChatId,
        model: state.config.model,
        length: trimmed.length,
      });

      const selectedTitleModel = state.currentTitleModel?.trim()?.length
        ? state.currentTitleModel.trim()
        : state.config.titleModel?.trim()?.length
          ? state.config.titleModel.trim()
          : undefined;
      const completionParameters = buildCompletionParameters(state.config);

      const userMessage: ChatMessage = {
        id: safeId(),
        role: "user",
        content: trimmed,
        createdAt: Date.now(),
      };

      set({
        messages: [...state.messages, userMessage],
        isLoading: true,
        error: undefined,
      });

      let chatId = state.currentChatId;
      try {
        if (!chatId) {
          const created = await apiClient.createChat({
            model: state.config.model,
            systemPrompt: state.currentSystemPrompt,
            parameters: completionParameters,
            ...(selectedTitleModel ? { titleModel: selectedTitleModel } : {}),
            messages: [toBackendMessage(userMessage)],
          });
          chatId = created.chatId;
          const persistedPrompt =
            created.systemPrompt?.trim()?.length
              ? created.systemPrompt.trim()
              : state.currentSystemPrompt;
          const persistedTitleModel =
            created.titleModel?.trim()?.length
              ? created.titleModel.trim()
              : state.currentTitleModel;

          set({
            currentChatId: chatId,
            messages: created.messages.map(fromBackendMessage),
            currentSystemPrompt: persistedPrompt ?? "",
            currentTitleModel: persistedTitleModel ?? null,
            currentSystemPromptPersisted: persistedPrompt ?? "",
            currentTitleModelPersisted: persistedTitleModel ?? null,
            hasUnsavedSystemPrompt: false,
            hasUnsavedTitleModel: false,
            isLoading: false,
            error: undefined,
          });
          void get().refreshChats();
        } else {
          const updated = await apiClient.sendChatMessage(chatId, {
            model: state.config.model,
            systemPrompt: state.currentSystemPrompt,
            parameters: completionParameters,
            message: toBackendMessage(userMessage),
          });
          set((current) => {
            const persistedPrompt = updated.systemPrompt?.trim()?.length
              ? updated.systemPrompt.trim()
              : "";
            const persistedTitle = updated.titleModel?.trim()?.length
              ? updated.titleModel.trim()
              : null;
            const shouldKeepPrompt = current.hasUnsavedSystemPrompt;
            const shouldKeepTitleModel = current.hasUnsavedTitleModel;
            return {
              messages: updated.messages.map(fromBackendMessage),
              currentSystemPrompt: shouldKeepPrompt
                ? current.currentSystemPrompt
                : persistedPrompt,
              currentTitleModel: shouldKeepTitleModel
                ? current.currentTitleModel
                : persistedTitle,
              currentSystemPromptPersisted: persistedPrompt,
              currentTitleModelPersisted: persistedTitle,
              hasUnsavedSystemPrompt: current.hasUnsavedSystemPrompt,
              hasUnsavedTitleModel: current.hasUnsavedTitleModel,
              isLoading: false,
              error: undefined,
            };
          });
          void get().refreshChats();
          if (!updated.title) {
            setTimeout(() => {
              void get().refreshChats();
            }, 2000);
          }
        }
      } catch (error) {
        const message = getErrorMessage(error);
        logger.error("Failed to sync user message with backend", {
          chatId,
          message,
        });
        set(() => ({
          isLoading: false,
          error: message,
        }));
        return;
      }
    },
    fetchModels: async () => {
      const state = get();
      try {
        logger.info("Fetching available models from backend proxy");
        const response = await apiClient.listModels();
        const models = response.data
          .map((item) => item.id)
          .filter((modelId): modelId is string => Boolean(modelId));

        if (!models.length) {
          throw new Error("No models returned by the server.");
        }

        const preferredModel =
          state.config.model && models.includes(state.config.model)
            ? state.config.model
            : models[0];
        const nextConfig: ChatConfig = {
          ...state.config,
          model: preferredModel,
        };
        persistConfig(nextConfig);

        set({
          availableModels: models,
          config: nextConfig,
        });

        logger.info("Models loaded", { count: models.length });

        return true;
      } catch (error) {
        logger.error("Failed to fetch models", error);
        set((current) => {
          const nextConfig: ChatConfig = { ...current.config, model: "" };
          persistConfig(nextConfig);
          return {
            availableModels: [],
            config: nextConfig,
          };
        });
        return false;
      }
    },
    resetModels: () =>
      set((state) => {
        const nextConfig: ChatConfig = { ...state.config, model: "" };
        persistConfig(nextConfig);
        return {
          availableModels: [],
          config: nextConfig,
        };
      }),
    resetConversation: () =>
      set((state) => {
        const defaultPrompt = state.config.systemPrompt;
        const defaultTitleModel = state.config.titleModel?.trim()?.length
          ? state.config.titleModel.trim()
          : null;
        return {
          messages: [],
          currentChatId: null,
          currentSystemPrompt: defaultPrompt,
          currentTitleModel: defaultTitleModel,
          currentSystemPromptPersisted: defaultPrompt,
          currentTitleModelPersisted: defaultTitleModel,
          hasUnsavedSystemPrompt: false,
          hasUnsavedTitleModel: false,
          error: undefined,
        };
      }),
    refreshChats: async () => {
      set({ isSyncing: true });
      try {
        const result = await apiClient.listChats();
        const summaries = result
          .map(
            (item): ChatSummary => ({
              chatId: item.chatId,
              title: item.title,
              createdAt: parseInstant(item.createdAt),
              updatedAt: parseInstant(item.updatedAt ?? item.createdAt),
              messageCount: item.messageCount,
            }),
          )
          .sort((a, b) => b.updatedAt - a.updatedAt);
        set({
          chatSummaries: summaries,
          isSyncing: false,
        });
      } catch (error) {
        set({
          isSyncing: false,
          error: getErrorMessage(error),
        });
      }
    },
    loadChat: async (chatId: string) => {
      set({ isLoading: true, error: undefined });
      try {
        const chat = await apiClient.getChat(chatId);
        const persistedPrompt = chat.systemPrompt?.trim()?.length
          ? chat.systemPrompt.trim()
          : "";
        const persistedTitleModel = chat.titleModel?.trim()?.length
          ? chat.titleModel.trim()
          : null;
        set({
          currentChatId: chat.chatId,
          messages: chat.messages.map(fromBackendMessage),
          currentSystemPrompt: persistedPrompt,
          currentTitleModel: persistedTitleModel,
          currentSystemPromptPersisted: persistedPrompt,
          currentTitleModelPersisted: persistedTitleModel,
          hasUnsavedSystemPrompt: false,
          hasUnsavedTitleModel: false,
          isLoading: false,
        });
        return true;
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          set({
            isLoading: false,
            currentChatId: null,
            messages: [],
            error: undefined,
          });
          return false;
        }
        set({
          isLoading: false,
          error: getErrorMessage(error),
        });
        return false;
      }
    },
    deleteChat: async (chatId: string) => {
      try {
        await apiClient.deleteChat(chatId);
        set((state) => {
          const summaries = state.chatSummaries.filter(
            (item) => item.chatId !== chatId,
          );
          const isCurrent = state.currentChatId === chatId;
          return {
            chatSummaries: summaries,
            ...(isCurrent
              ? {
                  currentChatId: null,
                  messages: [],
                  currentSystemPrompt: state.config.systemPrompt,
                  currentTitleModel:
                    state.config.titleModel?.trim()?.length
                      ? state.config.titleModel.trim()
                      : null,
                  currentSystemPromptPersisted: state.config.systemPrompt,
                  currentTitleModelPersisted:
                    state.config.titleModel?.trim()?.length
                      ? state.config.titleModel.trim()
                      : null,
                  hasUnsavedSystemPrompt: false,
                  hasUnsavedTitleModel: false,
                }
              : {}),
          };
        });
      } catch (error) {
        set({
          error: getErrorMessage(error),
        });
      }
    },
    updateChatMetadata: async (chatId, metadata) => {
      try {
        const updated = await apiClient.updateChat(chatId, metadata);
        const summary = toSummary(updated);
        set((state) => {
          const summaries = [summary, ...state.chatSummaries.filter((item) => item.chatId !== chatId)]
            .sort((a, b) => b.updatedAt - a.updatedAt);
          const isCurrent = state.currentChatId === chatId;
          const persistedPrompt = updated.systemPrompt?.trim()?.length
            ? updated.systemPrompt.trim()
            : "";
          const persistedTitle = updated.titleModel?.trim()?.length
            ? updated.titleModel.trim()
            : null;
          return {
            chatSummaries: summaries,
            ...(isCurrent
              ? {
                  messages: updated.messages.map(fromBackendMessage),
                  currentSystemPrompt: persistedPrompt,
                  currentTitleModel: persistedTitle,
                  currentSystemPromptPersisted: persistedPrompt,
                  currentTitleModelPersisted: persistedTitle,
                  hasUnsavedSystemPrompt: false,
                  hasUnsavedTitleModel: false,
                }
              : {}),
          };
        });
      } catch (error) {
        set({ error: getErrorMessage(error) });
      }
    },
    saveCurrentChatMetadata: async () => {
      const state = get();
      if (
        !state.currentChatId ||
        (!state.hasUnsavedSystemPrompt && !state.hasUnsavedTitleModel)
      ) {
        return;
      }
      const payload: BackendUpdateChatRequest = {};
      if (state.hasUnsavedSystemPrompt) {
        payload.systemPrompt =
          state.currentSystemPrompt.trim().length > 0
            ? state.currentSystemPrompt.trim()
            : null;
      }
      if (state.hasUnsavedTitleModel) {
        payload.titleModel = state.currentTitleModel;
      }
      if (Object.keys(payload).length === 0) {
        return;
      }
      await get().updateChatMetadata(state.currentChatId, payload);
    },
  };
});

function buildCompletionParameters(config: ChatConfig) {
  return {
    temperature: config.temperature,
    maxTokens: config.maxTokens,
    topP: config.topP,
    presencePenalty: config.presencePenalty,
    frequencyPenalty: config.frequencyPenalty,
  };
}

function toBackendMessage(message: ChatMessage) {
  return {
    messageId: message.id,
    role: message.role.toUpperCase() as "USER" | "ASSISTANT",
    content: message.content,
    createdAt: new Date(message.createdAt).toISOString(),
  };
}

function fromBackendMessage(message: BackendChatMessage) {
  const toolCalls = mapToolCalls(message.toolCalls);
  return {
    id: message.messageId,
    role: message.role.toLowerCase() as Role,
    content: message.content,
    createdAt: parseInstant(message.createdAt),
    ...(toolCalls.length > 0 ? { toolCalls } : {}),
  };
}

function mapToolCalls(toolCalls?: BackendToolCallInfo[]) {
  if (!toolCalls || toolCalls.length === 0) {
    return [];
  }
  return toolCalls.map((tool) => ({
    toolName: tool.toolName,
    server: tool.server,
    arguments: tool.arguments,
    result: tool.result,
    success: tool.success,
  } satisfies ToolCallInfo));
}

function parseInstant(value: string | null | undefined) {
  if (!value) {
    return Date.now();
  }
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? Date.now() : timestamp;
}

function getErrorMessage(error: unknown) {
  if (error instanceof Error) {
    return error.message;
  }
  if (typeof error === "string") {
    return error;
  }
  return "Unexpected error";
}

function toSummary(chat: BackendChat): ChatSummary {
  return {
    chatId: chat.chatId,
    title: chat.title,
    createdAt: parseInstant(chat.createdAt),
    updatedAt: parseInstant(chat.updatedAt ?? chat.createdAt),
    messageCount: chat.messages.length,
  };
}
