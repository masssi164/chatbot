import { describe, it, expect, beforeEach } from 'vitest';
import { useConfigStore } from './configStore';

describe('configStore', () => {
  beforeEach(() => {
    // Reset store state before each test
    useConfigStore.setState({
      model: 'gpt-4o',
      temperature: 0.7,
      maxTokens: 2000,
      topP: undefined,
      presencePenalty: undefined,
      frequencyPenalty: undefined,
      systemPrompt: undefined,
    });
  });

  it('should have default values', () => {
    const state = useConfigStore.getState();
    expect(state.model).toBe('gpt-4o');
    expect(state.temperature).toBe(0.7);
    expect(state.maxTokens).toBe(2000);
    expect(state.availableModels).toContain('gpt-4o');
  });

  it('should set model', () => {
    useConfigStore.getState().setModel('gpt-4-turbo');
    expect(useConfigStore.getState().model).toBe('gpt-4-turbo');
  });

  it('should set temperature', () => {
    useConfigStore.getState().setTemperature(0.9);
    expect(useConfigStore.getState().temperature).toBe(0.9);
  });

  it('should set maxTokens', () => {
    useConfigStore.getState().setMaxTokens(4000);
    expect(useConfigStore.getState().maxTokens).toBe(4000);
  });

  it('should set system prompt', () => {
    const prompt = 'You are a helpful assistant';
    useConfigStore.getState().setSystemPrompt(prompt);
    expect(useConfigStore.getState().systemPrompt).toBe(prompt);
  });

  it('should allow undefined values for optional settings', () => {
    useConfigStore.getState().setTemperature(undefined);
    useConfigStore.getState().setMaxTokens(undefined);
    
    const state = useConfigStore.getState();
    expect(state.temperature).toBeUndefined();
    expect(state.maxTokens).toBeUndefined();
  });

  it('should fetch models successfully', async () => {
    await useConfigStore.getState().fetchModels();
    const models = useConfigStore.getState().availableModels;
    
    expect(models).toBeInstanceOf(Array);
    expect(models.length).toBeGreaterThan(0);
    expect(models).toContain('gpt-4o');
  });

  it('should set topP', () => {
    useConfigStore.getState().setTopP(0.95);
    expect(useConfigStore.getState().topP).toBe(0.95);
  });

  it('should set presence penalty', () => {
    useConfigStore.getState().setPresencePenalty(0.5);
    expect(useConfigStore.getState().presencePenalty).toBe(0.5);
  });

  it('should set frequency penalty', () => {
    useConfigStore.getState().setFrequencyPenalty(0.3);
    expect(useConfigStore.getState().frequencyPenalty).toBe(0.3);
  });
});
