package app.chatbot.conversation;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tool_calls")
public class ToolCall {

    @Id
    private Long id;

    @Column("conversation_id")
    private Long conversationId;

    private ToolCallType type;

    private String name;

    @Column("call_id")
    private String callId;

    @Column("arguments_json")
    private String argumentsJson;

    @Column("result_json")
    private String resultJson;

    private ToolCallStatus status;

    @Column("output_index")
    private Integer outputIndex;

    @Column("item_id")
    private String itemId;

    @Column("created_at")
    private Instant createdAt;
}
