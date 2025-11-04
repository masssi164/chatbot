-- Make call_id nullable in tool_calls table
ALTER TABLE tool_calls ALTER COLUMN call_id VARCHAR(255) NULL;
