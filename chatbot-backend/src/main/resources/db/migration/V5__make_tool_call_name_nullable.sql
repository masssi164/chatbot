-- Migration V5: Make tool_calls.name nullable
-- Reason: name may not be available at tool call creation time (response.output_item.added event)
-- It gets populated later when response.function_call_arguments.done is received

ALTER TABLE tool_calls ALTER COLUMN name DROP NOT NULL;
