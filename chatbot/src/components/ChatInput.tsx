import type { FormEvent } from 'react'

interface ChatInputProps {
  prompt: string
  onPromptChange: (value: string) => void
  onSend: (value: string) => Promise<void> | void
  isLoading: boolean
  currentModel: string
  availableModels: string[]
  onModelChange: (model: string) => void
  disabled?: boolean
}

export function ChatInput({
  prompt,
  onPromptChange,
  onSend,
  isLoading,
  currentModel,
  availableModels,
  onModelChange,
  disabled = false,
}: ChatInputProps) {
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
        value={prompt}
        onChange={(event) => onPromptChange(event.target.value)}
        placeholder="Ask the assistant…"
        rows={3}
        disabled={isLoading}
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
      </div>
    </form>
  )
}

export default ChatInput
