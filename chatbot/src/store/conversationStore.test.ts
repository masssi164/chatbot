import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useConversationStore } from './conversationStore';
import { apiClient } from '../services/apiClient';

// Mock the API client
vi.mock('../services/apiClient', () => ({
  apiClient: {
    createConversation: vi.fn(),
    listConversations: vi.fn(),
    getConversation: vi.fn(),
  },
}));

// Helper to create mock conversation details
const createMockConversation = (overrides = {}) => ({
  id: 1,
  title: 'Test Chat',
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
  messages: [],
  toolCalls: [],
  status: 'CREATED' as const,
  ...overrides,
});

// Helper to create mock conversation summaries
const createMockSummary = (overrides = {}) => ({
  id: 1,
  title: 'Chat 1',
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
  status: 'COMPLETED' as const,
  messageCount: 5,
  ...overrides,
});

describe('conversationStore', () => {
  beforeEach(() => {
    // Reset store state before each test
    useConversationStore.setState({
      conversationId: null,
      conversationTitle: null,
      conversationSummaries: [],
      loading: false,
      error: null,
    });
    vi.clearAllMocks();
  });

  it('should have default values', () => {
    const state = useConversationStore.getState();
    expect(state.conversationId).toBeNull();
    expect(state.conversationTitle).toBeNull();
    expect(state.conversationSummaries).toEqual([]);
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it('should ensure conversation creates new one if none exists', async () => {
    const mockConversation = createMockConversation({ title: 'New Chat' });
    vi.mocked(apiClient.createConversation).mockResolvedValue(mockConversation);

    await useConversationStore.getState().ensureConversation();

    expect(apiClient.createConversation).toHaveBeenCalled();
    const state = useConversationStore.getState();
    expect(state.conversationId).toBe(1);
    expect(state.conversationTitle).toBe('New Chat');
  });

  it('should not create conversation if one already exists', async () => {
    useConversationStore.setState({ conversationId: 123 });

    await useConversationStore.getState().ensureConversation();

    expect(apiClient.createConversation).not.toHaveBeenCalled();
  });

  it('should load conversations list', async () => {
    const mockConversations = [
      createMockSummary({ id: 1, title: 'Chat 1', messageCount: 5 }),
      createMockSummary({ id: 2, title: 'Chat 2', messageCount: 3, createdAt: '2024-01-02', updatedAt: '2024-01-02' }),
    ];
    vi.mocked(apiClient.listConversations).mockResolvedValue(mockConversations);

    await useConversationStore.getState().loadConversations();

    expect(apiClient.listConversations).toHaveBeenCalled();
    const state = useConversationStore.getState();
    expect(state.conversationSummaries).toHaveLength(2);
    expect(state.conversationSummaries[0].title).toBe('Chat 1');
  });

  it('should load a specific conversation', async () => {
    const mockConversation = {
      id: 1,
      title: 'Test Chat',
      messages: [],
      toolCalls: [],
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
      status: 'COMPLETED' as const,
    };
    vi.mocked(apiClient.getConversation).mockResolvedValue(mockConversation);

    await useConversationStore.getState().loadConversation(1);

    expect(apiClient.getConversation).toHaveBeenCalledWith(1);
    const state = useConversationStore.getState();
    expect(state.conversationId).toBe(1);
    expect(state.conversationTitle).toBe('Test Chat');
  });

  it('should create a new conversation', async () => {
    const mockConversation = createMockConversation({ title: 'Custom Title' });
    vi.mocked(apiClient.createConversation).mockResolvedValue(mockConversation);

    const id = await useConversationStore.getState().createConversation('Custom Title');

    expect(apiClient.createConversation).toHaveBeenCalledWith({ title: 'Custom Title' });
    expect(id).toBe(1);
    const state = useConversationStore.getState();
    expect(state.conversationId).toBe(1);
    expect(state.conversationTitle).toBe('Custom Title');
    // Verify conversation was added to summaries list
    expect(state.conversationSummaries).toHaveLength(1);
    expect(state.conversationSummaries[0].title).toBe('Custom Title');
  });

  it('should set current conversation', () => {
    useConversationStore.getState().setCurrentConversation(123, 'My Chat');

    const state = useConversationStore.getState();
    expect(state.conversationId).toBe(123);
    expect(state.conversationTitle).toBe('My Chat');
  });

  it('should reset conversation state', () => {
    useConversationStore.setState({
      conversationId: 123,
      conversationTitle: 'Test',
      error: 'Some error',
    });

    useConversationStore.getState().reset();

    const state = useConversationStore.getState();
    expect(state.conversationId).toBeNull();
    expect(state.conversationTitle).toBeNull();
    expect(state.error).toBeNull();
  });

  it('should handle errors when loading conversations', async () => {
    const error = new Error('Network error');
    vi.mocked(apiClient.listConversations).mockRejectedValue(error);

    await useConversationStore.getState().loadConversations();

    const state = useConversationStore.getState();
    expect(state.error).toBe('Network error');
    expect(state.loading).toBe(false);
  });

  it('should handle errors when creating conversation', async () => {
    const error = new Error('Server error');
    vi.mocked(apiClient.createConversation).mockRejectedValue(error);

    await expect(
      useConversationStore.getState().createConversation()
    ).rejects.toThrow('Server error');

    const state = useConversationStore.getState();
    expect(state.error).toBe('Server error');
  });
});
