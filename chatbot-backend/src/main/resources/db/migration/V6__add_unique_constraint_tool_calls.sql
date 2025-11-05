-- Migration V6: Add unique constraint on (conversation_id, item_id)
-- Reason: Prevents duplicate tool calls with same item_id in a conversation
-- The findByConversationIdAndItemId query expects exactly one result

-- First, clean up any existing duplicates (keep the oldest entry)
DELETE FROM tool_calls
WHERE id NOT IN (
    SELECT MIN(id)
    FROM tool_calls
    GROUP BY conversation_id, item_id
);

-- Add unique constraint
ALTER TABLE tool_calls ADD CONSTRAINT uk_tool_calls_conversation_item 
    UNIQUE (conversation_id, item_id);
