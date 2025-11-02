package app.chatbot.mcp;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.chatbot.mcp.dto.McpServerDto;

@WebMvcTest(McpServerController.class)
class McpServerControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private McpServerService service;

    @MockBean
    private McpConnectionService connectionService;

    @Test
    void resolvesServerIdPathVariableOnGet() throws Exception {
        McpServerDto dto = new McpServerDto(
                "mcp-1",
                "LM Studio",
                "http://localhost:1234/v1",
                false,
                McpServerStatus.CONNECTED,
                McpTransport.STREAMABLE_HTTP,
                Instant.now()
        );
        when(service.getServer("mcp-1")).thenReturn(dto);

        mockMvc.perform(get("/api/mcp-servers/{serverId}", "mcp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").value("mcp-1"));

        verify(service).getServer("mcp-1");
    }

    @Test
    void resolvesServerIdPathVariableOnDelete() throws Exception {
        mockMvc.perform(delete("/api/mcp-servers/{serverId}", "mcp-2"))
                .andExpect(status().isNoContent());

        verify(service).delete("mcp-2");
    }
}
