import { useState } from "react";

interface SystemPromptPanelProps {
  value?: string;
  onChange: (value: string | undefined) => void;
}

const DEFAULT_PROMPTS = [
  {
    name: "Standard",
    prompt: undefined,
  },
  {
    name: "Präzise & Kurz",
    prompt: "Du bist ein präziser Assistent. Antworte kurz und direkt auf den Punkt.",
  },
  {
    name: "Kreativ",
    prompt: "Du bist ein kreativer Assistent mit viel Fantasie. Nutze bildhafte Sprache und denke außerhalb der Box.",
  },
  {
    name: "Technisch",
    prompt: "Du bist ein technischer Experte. Gib detaillierte, technisch präzise Antworten mit Code-Beispielen wo relevant.",
  },
  {
    name: "Lehrend",
    prompt: "Du bist ein geduldiger Lehrer. Erkläre Konzepte schrittweise und vergewissere dich, dass der Lernende folgen kann.",
  },
];

function SystemPromptPanel({ value, onChange }: SystemPromptPanelProps) {
  const [customPrompt, setCustomPrompt] = useState(value || "");
  const [isCustom, setIsCustom] = useState(
    value !== undefined && !DEFAULT_PROMPTS.some((p) => p.prompt === value)
  );

  const handlePresetChange = (prompt: string | undefined) => {
    setIsCustom(false);
    setCustomPrompt(prompt || "");
    onChange(prompt);
  };

  const handleCustomChange = (newValue: string) => {
    setCustomPrompt(newValue);
    onChange(newValue.trim() || undefined);
  };

  const handleCustomToggle = () => {
    if (!isCustom) {
      setIsCustom(true);
      setCustomPrompt(value || "");
    } else {
      setIsCustom(false);
      onChange(undefined);
      setCustomPrompt("");
    }
  };

  return (
    <section className="panel system-prompt">
      <h2>System Prompt</h2>
      <p className="hint" style={{ marginBottom: "1rem" }}>
        Der System-Prompt definiert das Verhalten und die Persönlichkeit des Assistenten.
      </p>

      <div className="preset-buttons" style={{ marginBottom: "1rem", display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
        {DEFAULT_PROMPTS.map((preset) => (
          <button
            key={preset.name}
            type="button"
            className={`secondary ${!isCustom && value === preset.prompt ? "active" : ""}`}
            onClick={() => handlePresetChange(preset.prompt)}
            style={{
              background: !isCustom && value === preset.prompt ? "#0066cc" : undefined,
              color: !isCustom && value === preset.prompt ? "white" : undefined,
            }}
          >
            {preset.name}
          </button>
        ))}
      </div>

      <div style={{ marginBottom: "1rem" }}>
        <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}>
          <input
            type="checkbox"
            checked={isCustom}
            onChange={handleCustomToggle}
          />
          <span>Eigener System-Prompt</span>
        </label>
      </div>

      {isCustom && (
        <div>
          <textarea
            value={customPrompt}
            onChange={(e) => handleCustomChange(e.target.value)}
            placeholder="Beschreibe hier das gewünschte Verhalten des Assistenten..."
            rows={8}
            style={{
              width: "100%",
              fontFamily: "inherit",
              fontSize: "0.95rem",
              padding: "0.75rem",
              border: "1px solid #ccc",
              borderRadius: "4px",
              resize: "vertical",
            }}
          />
          <p className="hint" style={{ marginTop: "0.5rem" }}>
            Zeichen: {customPrompt.length}
          </p>
        </div>
      )}

      {!isCustom && value && (
        <div
          style={{
            padding: "1rem",
            background: "#f5f5f5",
            borderRadius: "4px",
            fontSize: "0.95rem",
            color: "#666",
          }}
        >
          <strong>Aktueller Prompt:</strong>
          <p style={{ marginTop: "0.5rem", marginBottom: 0 }}>
            {value || <em>Kein System-Prompt gesetzt (Standard)</em>}
          </p>
        </div>
      )}
    </section>
  );
}

export default SystemPromptPanel;
