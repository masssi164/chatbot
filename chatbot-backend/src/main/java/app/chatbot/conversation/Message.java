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
@Table("messages")
public class Message {

    @Id
    private Long id;

    @Column("conversation_id")
    private Long conversationId;

    private MessageRole role;

    private String content;

    @Column("raw_json")
    private String rawJson;

    @Column("output_index")
    private Integer outputIndex;

    @Column("item_id")
    private String itemId;

    @Column("created_at")
    private Instant createdAt;
}
