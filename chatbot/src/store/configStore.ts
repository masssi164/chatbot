import { create } from "zustand";
import { apiClient } from "../services/apiClient";

/**
 * Configuration type for LLM model settings (data only, no actions)
 * Used for passing config to components
 */
export interface ChatConfig {
  model: string;
  titleModel?: string;
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
  systemPrompt?: string;
}

/**
 * Configuration store for LLM model settings
 * Extracted from chatStore for better maintainability
 */
export interface ConfigState {
  model: string;
  availableModels: string[];
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
  systemPrompt?: string;
  
  // Actions
  fetchModels: () => Promise<void>;
  setModel: (model: string) => void;
  setTemperature: (value: number | undefined) => void;
  setMaxTokens: (value: number | undefined) => void;
  setTopP: (value: number | undefined) => void;
  setPresencePenalty: (value: number | undefined) => void;
  setFrequencyPenalty: (value: number | undefined) => void;
  setSystemPrompt: (value: string | undefined) => void;
}

export const useConfigStore = create<ConfigState>((set) => ({
  model: "gpt-4o",
  availableModels: [],
  temperature: 0.7,
  maxTokens: 2000,
  topP: undefined,
  presencePenalty: undefined,
  frequencyPenalty: undefined,
  systemPrompt: undefined,

  fetchModels: async () => {
    try {
      const models = await apiClient.fetchModels();
      if (!Array.isArray(models) || models.length === 0) {
        set({ availableModels: [] });
        return;
      }

      set((state) => {
        const current = state.model;
        const nextModel = current && models.includes(current)
          ? current
          : models[0];

        return {
          availableModels: models,
          model: nextModel ?? current,
        };
      });
    } catch (error) {
      console.error("Failed to fetch models:", error);
    }
  },

  setModel: (model: string) => set({ model }),
  setTemperature: (value: number | undefined) => set({ temperature: value }),
  setMaxTokens: (value: number | undefined) => set({ maxTokens: value }),
  setTopP: (value: number | undefined) => set({ topP: value }),
  setPresencePenalty: (value: number | undefined) => set({ presencePenalty: value }),
  setFrequencyPenalty: (value: number | undefined) => set({ frequencyPenalty: value }),
  setSystemPrompt: (value: string | undefined) => set({ systemPrompt: value }),
}));
