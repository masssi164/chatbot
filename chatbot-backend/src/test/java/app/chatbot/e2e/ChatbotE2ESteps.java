package app.chatbot.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ChatbotE2ESteps {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(90);
    private static WebDriver driver;
    private static HttpClient httpClient;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String frontendBase = envOrProperty("FRONTEND_BASE_URL", "http://localhost:3000");
    private static final String backendBase = envOrProperty("BACKEND_BASE_URL", "http://localhost:8080");
    private static final String litellmBase = envOrProperty("LITELLM_API_BASE_URL", "http://localhost:4000");
    private static final String n8nMcpUrl = envOrProperty("N8N_MCP_URL", "http://localhost:5678/api/mcp/sse");
    private static final String mcpServerId = envOrProperty("E2E_MCP_SERVER_ID", "n8n-e2e");

    @BeforeAll
    public static void startDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage");
        options.setAcceptInsecureCerts(true);
        var manager = WebDriverManager.chromedriver()
                .browserVersion("stable")
                .capabilities(options);
        manager.setup(); // downloads driver and Chrome-for-Testing binary when missing
        var browserPath = manager.getBrowserPath().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(browserPath != null, "Chrome binary not available for E2E");
        options.setBinary(browserPath.toString());
        driver = new ChromeDriver(options);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @AfterAll
    public static void stopDriver() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Given("the stack is healthy")
    public void theStackIsHealthy() throws Exception {
        assertHealthy(frontendBase);
        assertHealthy(backendBase + "/actuator/health");
        assertHealthy(litellmBase + "/health/liveliness");
    }

    @When("I open the chatbot UI")
    public void openChatbotUi() {
        driver.get(frontendBase);
        WebDriverWait wait = new WebDriverWait(driver, UI_TIMEOUT);
        wait.until(d -> d.findElement(By.cssSelector("textarea[placeholder='Ask the assistant…']")));
        waitForModelOptions(wait);
    }

    @When("I submit a chat message {string}")
    public void submitChatMessage(String message) {
        WebDriverWait wait = new WebDriverWait(driver, UI_TIMEOUT);
        waitForModelOptions(wait);

        WebElement input = driver.findElement(By.cssSelector("textarea[placeholder='Ask the assistant…']"));
        input.clear();
        input.sendKeys(message);

        WebElement sendButton = driver.findElement(By.cssSelector("button[type='submit']"));
        wait.until((ExpectedCondition<Boolean>) d -> sendButton.isEnabled());
        sendButton.click();
    }

    @Then("I see an assistant reply")
    public void iSeeAnAssistantReply() {
        WebDriverWait wait = new WebDriverWait(driver, UI_TIMEOUT);
        WebElement assistantMessage = wait.until(driver -> {
            List<WebElement> messages = driver.findElements(By.cssSelector(".chat-message.assistant .chat-bubble"));
            for (WebElement message : messages) {
                String text = message.getText().trim();
                if (!text.isBlank() && !text.toLowerCase().contains("thinking")) {
                    return message;
                }
            }
            return null;
        });
        assertNotNull(assistantMessage, "Expected an assistant reply to appear");
    }

    @When("I upsert the default n8n MCP server")
    public void upsertDefaultN8nMcpServer() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("serverId", mcpServerId);
        payload.put("name", "n8n-e2e");
        payload.put("baseUrl", n8nMcpUrl);
        payload.put("transport", "SSE");
        payload.put("requireApproval", "never");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backendBase + "/api/mcp-servers"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, "MCP server upsert failed: " + response.statusCode() + " " + response.body());
    }

    @Then("LiteLLM lists the server via the backend")
    public void litellmListsServer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backendBase + "/api/mcp-servers"))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, "Failed to fetch MCP servers: " + response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.isArray(), "Unexpected MCP servers response payload");

        boolean found = false;
        for (JsonNode node : root) {
            if (mcpServerId.equalsIgnoreCase(node.path("serverId").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected MCP server " + mcpServerId + " to be present");
    }

    private static void assertHealthy(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, "Health check failed for " + url + " status=" + response.statusCode());

        Optional.ofNullable(parseStatus(response.body()))
                .ifPresent(status -> assertFalse("DOWN".equalsIgnoreCase(status), "Service " + url + " reported DOWN"));
    }

    private static void waitForModelOptions(WebDriverWait wait) {
        wait.until(driver -> {
            Select select = new Select(driver.findElement(By.cssSelector("select")));
            return select.getOptions().stream().anyMatch(option -> !option.getAttribute("value").isBlank());
        });
    }

    private static String envOrProperty(String key, String fallback) {
        String fromProperty = System.getProperty(key);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return fallback;
    }

    private static String parseStatus(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            return node.path("status").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
