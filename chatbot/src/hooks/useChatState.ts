import { useChatStore } from "../store/chatStore";

/**
 * Custom hook to group chat state selectors and reduce boilerplate
 */
export function useChatState() {
  return {
    // Conversation state
    conversationId: useChatStore((state) => state.conversationId),
    conversationTitle: useChatStore((state) => state.conversationTitle),
    conversationSummaries: useChatStore((state) => state.conversationSummaries),
    
    // Message state
    messages: useChatStore((state) => state.messages),
    toolCalls: useChatStore((state) => state.toolCalls),
    
    // Stream state
    isStreaming: useChatStore((state) => state.isStreaming),
    streamError: useChatStore((state) => state.streamError),
    
    // Model configuration
    model: useChatStore((state) => state.model),
    availableModels: useChatStore((state) => state.availableModels),
    temperature: useChatStore((state) => state.temperature),
    maxTokens: useChatStore((state) => state.maxTokens),
    topP: useChatStore((state) => state.topP),
    presencePenalty: useChatStore((state) => state.presencePenalty),
    frequencyPenalty: useChatStore((state) => state.frequencyPenalty),
    systemPrompt: useChatStore((state) => state.systemPrompt),
  };
}

/**
 * Custom hook to group chat actions and reduce boilerplate
 */
export function useChatActions() {
  return {
    // Conversation actions
    ensureConversation: useChatStore((state) => state.ensureConversation),
    loadConversations: useChatStore((state) => state.loadConversations),
    loadConversation: useChatStore((state) => state.loadConversation),
    reset: useChatStore((state) => state.reset),
    
    // Message actions
    sendMessage: useChatStore((state) => state.sendMessage),
    abortStreaming: useChatStore((state) => state.abortStreaming),
    
    // Model configuration actions
    fetchModels: useChatStore((state) => state.fetchModels),
    setModel: useChatStore((state) => state.setModel),
    setTemperature: useChatStore((state) => state.setTemperature),
    setMaxTokens: useChatStore((state) => state.setMaxTokens),
    setTopP: useChatStore((state) => state.setTopP),
    setPresencePenalty: useChatStore((state) => state.setPresencePenalty),
    setFrequencyPenalty: useChatStore((state) => state.setFrequencyPenalty),
    setSystemPrompt: useChatStore((state) => state.setSystemPrompt),
  };
}
