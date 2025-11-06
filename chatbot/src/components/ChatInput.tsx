import {
    forwardRef,
    useImperativeHandle,
    useMemo,
    useRef,
    useState,
    type ChangeEvent,
    type FormEvent,
} from 'react'

interface CompletionParametersProps {
  temperature?: number
  maxTokens?: number
  topP?: number
  presencePenalty?: number
  frequencyPenalty?: number
}

interface ChatInputProps {
  prompt: string
  onPromptChange: (value: string) => void
  onSend: (value: string) => Promise<void> | void
  onAbort?: () => void
  isLoading: boolean
  currentModel: string
  availableModels: string[]
  onModelChange: (model: string) => void
  parameters: CompletionParametersProps
  onParametersChange: {
    setTemperature: (value: number | undefined) => void
    setMaxTokens: (value: number | undefined) => void
    setTopP: (value: number | undefined) => void
    setPresencePenalty: (value: number | undefined) => void
    setFrequencyPenalty: (value: number | undefined) => void
  }
  disabled?: boolean
}

export interface ChatInputHandle {
  focusPrompt: () => void
}

const ChatInput = forwardRef<ChatInputHandle, ChatInputProps>(
  (
    {
      prompt,
      onPromptChange,
      onSend,
      onAbort,
      isLoading,
      currentModel,
      availableModels,
      onModelChange,
      parameters,
      onParametersChange,
      disabled = false,
    },
    ref,
  ) => {
    const [showParameters, setShowParameters] = useState(false)
    const textareaRef = useRef<HTMLTextAreaElement | null>(null)

    useImperativeHandle(
      ref,
      () => ({
        focusPrompt: () => {
          textareaRef.current?.focus()
        },
      }),
      [],
    )
    const parameterControls = useMemo(
      () => [
      {
        id: 'temperature',
        label: 'Temperature',
        value: parameters.temperature,
        min: 0,
        max: 2,
        step: 0.1,
        description: 'Controls randomness. Higher values produce more creative but less deterministic replies.',
        setter: onParametersChange.setTemperature,
      },
      {
        id: 'max-tokens',
        label: 'Max tokens',
        value: parameters.maxTokens,
        min: 0,
        step: 1,
        description: 'Caps the length of the assistant response in tokens. Leave empty to allow the model default.',
        setter: onParametersChange.setMaxTokens,
      },
      {
        id: 'top-p',
        label: 'Top-p',
        value: parameters.topP,
        min: 0,
        max: 1,
        step: 0.01,
        description: 'Limits sampling to the most likely tokens whose cumulative probability reaches this value.',
        setter: onParametersChange.setTopP,
      },
      {
        id: 'presence',
        label: 'Presence penalty',
        value: parameters.presencePenalty,
        min: -2,
        max: 2,
        step: 0.1,
        description: 'Encourages introducing new topics. Positive numbers reduce repetition of existing concepts.',
        setter: onParametersChange.setPresencePenalty,
      },
      {
        id: 'frequency',
        label: 'Frequency penalty',
        value: parameters.frequencyPenalty,
        min: -2,
        max: 2,
        step: 0.1,
        description: 'Penalises repeated tokens. Higher values make the model less likely to repeat itself.',
        setter: onParametersChange.setFrequencyPenalty,
      },
      ],
      [
        onParametersChange.setFrequencyPenalty,
        onParametersChange.setMaxTokens,
        onParametersChange.setPresencePenalty,
        onParametersChange.setTemperature,
        onParametersChange.setTopP,
        parameters.frequencyPenalty,
        parameters.maxTokens,
        parameters.presencePenalty,
        parameters.temperature,
        parameters.topP,
      ],
    )

    const makeNumberChangeHandler =
      (setter: (value: number | undefined) => void) =>
      (event: ChangeEvent<HTMLInputElement>) => {
        const raw = event.target.value
        setter(raw === '' ? undefined : Number(raw))
      }

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault()
      const value = prompt.trim()

      if (!value) {
        return
      }

      onPromptChange('')
      await onSend(value)
    }

    return (
      <form className="composer" onSubmit={handleSubmit}>
      <textarea
        ref={textareaRef}
        value={prompt}
        onChange={(event) => onPromptChange(event.target.value)}
        placeholder="Ask the assistant…"
        rows={3}
        disabled={isLoading}
        title="Shortcut: Shift+Escape focuses this input"
      />
      <div className="composer-actions">
        <label className="model-picker">
          <span>Model</span>
          <select
            value={currentModel}
            onChange={(event) => onModelChange(event.target.value)}
            disabled={availableModels.length === 0 || isLoading}
          >
            <option value="" disabled>
              {availableModels.length === 0 ? 'No models available' : 'Select a model'}
            </option>
            {availableModels.map((model) => (
              <option key={model} value={model}>
                {model}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="secondary"
          onClick={() => setShowParameters((current) => !current)}
          disabled={isLoading}
        >
          {showParameters ? 'Hide payload' : 'Payload options'}
        </button>
        <button
          type="submit"
          disabled={
            isLoading ||
            disabled ||
            prompt.trim().length === 0 ||
            availableModels.length === 0 ||
            !currentModel.trim()
          }
        >
          {isLoading ? 'Sending…' : 'Send'}
        </button>
        {onAbort && (
          <button
            type="button"
            className="secondary"
            onClick={onAbort}
            disabled={!isLoading}
          >
            Stop
          </button>
        )}
      </div>
      {showParameters && (
        <div className="payload-panel">
          <div className="payload-grid">
            {parameterControls.map(
              ({ id, label, value, min, max, step, description, setter }) => (
                <div key={id} className="payload-item">
                  <label htmlFor={`payload-${id}`}>{label}</label>
                  <input
                    id={`payload-${id}`}
                    type="number"
                    value={value ?? ''}
                    min={min}
                    max={max}
                    step={step}
                    onChange={makeNumberChangeHandler(setter)}
                  />
                  <p className="payload-description">{description}</p>
                </div>
              ),
            )}
          </div>
        </div>
      )}
      </form>
    )
  },
);

export default ChatInput;
