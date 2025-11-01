package app.chatbot.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OpenAiProxyController.class)
class OpenAiProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OpenAiProxyService proxyService;

    @Test
    void postResponsesDelegatesToService() throws Exception {
        ResponseEntity<String> downstream = ResponseEntity.ok("{\"id\":\"resp_1\"}");
        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        when(proxyService.createResponse(payloadCaptor.capture(), eq("Bearer demo")))
                .thenReturn(downstream);

        mockMvc.perform(post("/api/openai/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer demo")
                        .content("""
                                {"model":"test","input":[{"role":"user","content":"Hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"resp_1\"}"));

        JsonNode capturedPayload = payloadCaptor.getValue();
        assertThat(capturedPayload.path("model").asText()).isEqualTo("test");
        verify(proxyService).createResponse(capturedPayload, "Bearer demo");
    }

    @Test
    void postChatCompletionsDelegatesToService() throws Exception {
        ResponseEntity<String> downstream = ResponseEntity.ok("{\"choices\":[]}");
        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        when(proxyService.createChatCompletion(payloadCaptor.capture(), isNull()))
                .thenReturn(downstream);

        mockMvc.perform(post("/api/openai/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"test","messages":[{"role":"user","content":"Hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"choices\":[]}"));

        JsonNode capturedPayload = payloadCaptor.getValue();
        assertThat(capturedPayload.path("model").asText()).isEqualTo("test");
        verify(proxyService).createChatCompletion(capturedPayload, null);
    }

    @Test
    void getModelsDelegatesToService() throws Exception {
        ResponseEntity<String> downstream = ResponseEntity.ok("{\"data\":[{\"id\":\"model-1\"}]}");
        when(proxyService.listModels(null)).thenReturn(downstream);

        mockMvc.perform(get("/api/openai/models"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":[{\"id\":\"model-1\"}]}"));

        verify(proxyService).listModels(null);
    }
}
