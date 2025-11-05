-- V4: Add conversation lifecycle tracking
-- 
-- Purpose: Track OpenAI response lifecycle states to properly handle
-- response.created, response.completed, response.incomplete, and response.failed events.

-- Add response_id from OpenAI (set by response.created event)
ALTER TABLE conversations 
ADD COLUMN response_id VARCHAR(255);

-- Add status to track conversation lifecycle
ALTER TABLE conversations 
ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'CREATED';

-- Add completion_reason for incomplete/failed states
ALTER TABLE conversations 
ADD COLUMN completion_reason VARCHAR(255);

-- Create index for response_id lookups
CREATE INDEX idx_conversations_response_id ON conversations(response_id);

-- Create index for status queries (monitoring, analytics)
CREATE INDEX idx_conversations_status ON conversations(status);

-- Add documentation
COMMENT ON COLUMN conversations.response_id IS 'OpenAI response ID from response.created event. Used for tracking response lifecycle.';
COMMENT ON COLUMN conversations.status IS 'Lifecycle status: CREATED (new), STREAMING (active), COMPLETED (success), INCOMPLETE (token limit), FAILED (error)';
COMMENT ON COLUMN conversations.completion_reason IS 'Reason for INCOMPLETE status (e.g., length, error) or FAILED status (error code)';
