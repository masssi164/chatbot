import { useConversationStore } from "../store/conversationStore";
import { useMessageStore } from "../store/messageStore";
import { useStreamingStore } from "../store/streamingStore";
import { useToolCallStore } from "../store/toolCallStore";
import { useConfigStore } from "../store/configStore";

/**
 * Custom hook to group chat state selectors from modular stores
 * Refactored to use focused stores instead of monolithic chatStore
 */
export function useChatState() {
  return {
    // Conversation state (from conversationStore)
    conversationId: useConversationStore((state) => state.conversationId),
    conversationTitle: useConversationStore((state) => state.conversationTitle),
    conversationSummaries: useConversationStore((state) => state.conversationSummaries),
    
    // Message state (from messageStore)
    messages: useMessageStore((state) => state.messages),
    
    // Tool call state (from toolCallStore)
    toolCalls: useToolCallStore((state) => state.toolCalls),
    pendingApprovalRequest: useToolCallStore((state) => state.pendingApprovalRequest),
    
    // Stream state (from streamingStore)
    isStreaming: useStreamingStore((state) => state.isStreaming),
    streamError: useStreamingStore((state) => state.streamError),
    
    // Response lifecycle tracking (from streamingStore)
    responseId: useStreamingStore((state) => state.responseId),
    conversationStatus: useStreamingStore((state) => state.conversationStatus),
    completionReason: useStreamingStore((state) => state.completionReason),
    
    // Model configuration (from configStore)
    model: useConfigStore((state) => state.model),
    availableModels: useConfigStore((state) => state.availableModels),
    temperature: useConfigStore((state) => state.temperature),
    maxTokens: useConfigStore((state) => state.maxTokens),
    topP: useConfigStore((state) => state.topP),
    presencePenalty: useConfigStore((state) => state.presencePenalty),
    frequencyPenalty: useConfigStore((state) => state.frequencyPenalty),
    systemPrompt: useConfigStore((state) => state.systemPrompt),
  };
}

/**
 * Custom hook to group chat actions from modular stores
 * Refactored to use focused stores instead of monolithic chatStore
 */
export function useChatActions() {
  return {
    // Conversation actions (from conversationStore)
    ensureConversation: useConversationStore((state) => state.ensureConversation),
    loadConversations: useConversationStore((state) => state.loadConversations),
    loadConversation: useConversationStore((state) => state.loadConversation),
    
    // Reset actions (from multiple stores)
    reset: () => {
      useConversationStore.getState().reset();
      useMessageStore.getState().clearMessages();
      useStreamingStore.getState().reset();
      useToolCallStore.getState().clearToolCalls();
    },
    
    // Message actions (from streamingStore)
    sendMessage: useStreamingStore((state) => state.sendMessage),
    abortStreaming: useStreamingStore((state) => state.abortStreaming),
    sendApprovalResponse: useStreamingStore((state) => state.sendApprovalResponse),
    
    // Model configuration actions (from configStore)
    fetchModels: useConfigStore((state) => state.fetchModels),
    setModel: useConfigStore((state) => state.setModel),
    setTemperature: useConfigStore((state) => state.setTemperature),
    setMaxTokens: useConfigStore((state) => state.setMaxTokens),
    setTopP: useConfigStore((state) => state.setTopP),
    setPresencePenalty: useConfigStore((state) => state.setPresencePenalty),
    setFrequencyPenalty: useConfigStore((state) => state.setFrequencyPenalty),
    setSystemPrompt: useConfigStore((state) => state.setSystemPrompt),
  };
}
