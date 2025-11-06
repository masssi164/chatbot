-- V3: Refactor tool_call IDs - Clarify call_id vs item_id
-- 
-- Purpose: Add documentation and indexing to clarify that item_id is the primary
-- identifier from OpenAI API, while call_id is optional and may not always be present.

-- Add comments to clarify field semantics
COMMENT ON COLUMN tool_calls.call_id IS 'Optional tool-specific ID from OpenAI. If not present, defaults to item_id. May be missing in REST Responses API.';
COMMENT ON COLUMN tool_calls.item_id IS 'Primary identifier from OpenAI (unique per output item). Always present in API responses.';

-- Create index on item_id for fast lookups (primary identifier)
CREATE INDEX IF NOT EXISTS idx_tool_calls_item_id ON tool_calls(conversation_id, item_id);

-- Keep existing call_id index for compatibility
-- (idx_tool_calls_call_id should already exist from V1)
