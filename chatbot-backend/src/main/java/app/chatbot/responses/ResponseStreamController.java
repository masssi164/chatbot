package app.chatbot.responses;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping(path = "/api/responses", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@Slf4j
public class ResponseStreamController {

    private final ResponseStreamService streamService;

    public ResponseStreamController(ResponseStreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Flux<ServerSentEvent<String>> streamResponses(@RequestBody ResponseStreamRequest request,
                                                         @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                                         String authorization) {
        String model = request.payload() != null && request.payload().has("model") 
                ? request.payload().get("model").asText() 
                : "unknown";
        
        log.info("📨 Received stream request for conversation: {}, model: {}", 
                 request.conversationId(), model);
        log.debug("Full request: {}", request);
        
        return streamService.streamResponses(request, authorization)
                .doOnSubscribe(s -> log.info("✅ Client subscribed to stream"))
                .doOnComplete(() -> log.info("✅ Stream completed successfully"))
                .doOnError(e -> log.error("❌ Stream error occurred", e));
    }
}
