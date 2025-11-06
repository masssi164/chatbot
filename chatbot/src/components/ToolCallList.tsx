import { useChatStore } from "../store/chatStore";
import { ToolCallDetails } from "./ToolCallDetails";
import "./ToolCallList.css";

interface ToolCallListProps {
  readonly conversationId: number | null;
}

export function ToolCallList({ conversationId }: ToolCallListProps) {
  const toolCalls = useChatStore((state) => state.toolCalls);

  if (!conversationId || toolCalls.length === 0) {
    return null;
  }

  return (
    <div className="tool-call-list">
      <h3>Tool Executions ({toolCalls.length})</h3>
      {toolCalls.map((toolCall) => (
        <ToolCallDetails key={toolCall.itemId} toolCall={toolCall} />
      ))}
    </div>
  );
}
