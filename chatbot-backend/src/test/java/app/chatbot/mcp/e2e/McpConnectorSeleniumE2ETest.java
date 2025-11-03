package app.chatbot.mcp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import app.chatbot.ChatbotBackendApplication;
import app.chatbot.mcp.McpServer;
import app.chatbot.mcp.McpServerRepository;
import app.chatbot.mcp.McpTransport;
import app.chatbot.mcp.McpServerStatus;
import io.github.bonigarcia.wdm.WebDriverManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

/**
 * End-to-end Selenium test that exercises the full stack (frontend + backend) by adding a new MCP
 * connector through the React UI and verifying the backend persists and connects to it.
 */
@SpringBootTest(
        classes = ChatbotBackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpConnectorSeleniumE2ETest {

    private static final Path FRONTEND_DIR =
            Path.of("..", "chatbot").toAbsolutePath().normalize();
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration FRONTEND_START_TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    private int backendPort;

    @Autowired
    private McpServerRepository mcpServerRepository;

    private WebDriver driver;
    private Process frontendProcess;
    private ConfigurableApplicationContext mcpServerContext;
    private int frontendPort;
    private boolean frontendBuilt = false;

    @BeforeAll
    void setUpClass() throws Exception {
        System.setProperty("webdriver.http.factory", "jdk-http-client");
        var manager = WebDriverManager.chromedriver();
        manager.setup();
        var browserPath = manager.getBrowserPath();
        Assumptions.assumeTrue(browserPath.isPresent(),
                "Chrome binary not available on host. Install Chrome or provide CHROME_BIN to run Selenium E2E test.");
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--window-size=1280,960");
        options.setBinary(browserPath.get().toFile().getAbsolutePath());
        driver = new ChromeDriver(options);

        ensureFrontendDependencies();
    }

    @AfterAll
    void tearDownClass() {
        if (driver != null) {
            driver.quit();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        startSpringAiServer();
        ensureFrontendBuilt();
        startFrontendPreview();
        waitForFrontend();
    }

    @AfterEach
    void cleanUp() {
        if (frontendProcess != null && frontendProcess.isAlive()) {
            frontendProcess.destroy();
            try {
                if (!frontendProcess.waitFor(5, TimeUnit.SECONDS)) {
                    frontendProcess.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                frontendProcess.destroyForcibly();
            }
        }
        frontendProcess = null;

        if (mcpServerContext != null) {
            mcpServerContext.close();
            mcpServerContext = null;
        }

        mcpServerRepository.deleteAll();
    }

    @Test
    void shouldAddConnectorViaUiAndReachConnectedState() {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(45));
        driver.get("http://127.0.0.1:" + frontendPort + "/");

        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(.,'Show settings')]"))).click();

        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(@class,'settings-tab') and contains(.,'Konnektoren')]")))
                .click();

        WebElement nameInput = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//label[span[text()='Name']]/input")));
        nameInput.clear();
        nameInput.sendKeys("Spring AI SSE");

        WebElement baseUrlInput = driver.findElement(
                By.xpath("//label[span[text()='Base URL']]/input"));
        baseUrlInput.clear();
        baseUrlInput.sendKeys(getSpringAiBaseUrl());

        Select transportSelect = new Select(driver.findElement(
                By.xpath("//label[span[text()='Transport']]/select")));
        transportSelect.selectByVisibleText("SSE (deprecated)");

        WebElement addButton = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//form[contains(@class,'connector-form')]//button[contains(.,'Hinzufügen')]")));
        addButton.click();

        WebElement statusBadge = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//li[contains(@class,'connector-item')]//span[contains(@class,'connector-name') and text()='Spring AI SSE']/../span[contains(@class,'connector-status')]")));

        wait.until(ExpectedConditions.textToBePresentInElement(statusBadge, "Connected"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    List<McpServer> servers = mcpServerRepository.findAll();
                    assertThat(servers).hasSize(1);
                    McpServer server = servers.get(0);
                    assertThat(server.getStatus()).isEqualTo(McpServerStatus.CONNECTED);
                    assertThat(server.getTransport()).isEqualTo(McpTransport.SSE);
                    assertThat(server.getBaseUrl()).isEqualTo(getSpringAiBaseUrl());
                });
    }

    private void ensureFrontendDependencies() throws Exception {
        if (!Files.exists(FRONTEND_DIR)) {
            throw new IllegalStateException("Frontend directory not found at " + FRONTEND_DIR);
        }
        runCommand(FRONTEND_DIR, COMMAND_TIMEOUT, "npm", "install");
    }

    private void ensureFrontendBuilt() throws Exception {
        if (frontendBuilt) {
            return;
        }
        runCommand(FRONTEND_DIR, COMMAND_TIMEOUT, "npm", "run", "build");
        frontendBuilt = true;
    }

    private void startFrontendPreview() throws IOException {
        frontendPort = findFreePort();
        ProcessBuilder builder = new ProcessBuilder("npm", "run", "preview", "--",
                "--host", "127.0.0.1",
                "--port", String.valueOf(frontendPort),
                "--strictPort");
        builder.directory(FRONTEND_DIR.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("VITE_BACKEND_API", "http://localhost:" + backendPort + "/api");
        env.putIfAbsent("BROWSER", "none");
        frontendProcess = builder.start();
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(frontendProcess.getInputStream()))) {
                reader.lines().forEach(line -> System.out.println("[vite] " + line));
            } catch (IOException ignored) {
            }
        });
        logThread.setDaemon(true);
        logThread.start();
    }

    private void waitForFrontend() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + frontendPort + "/");
        Awaitility.await()
                .atMost(FRONTEND_START_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    try {
                        HttpRequest request = HttpRequest.newBuilder(uri)
                                .GET()
                                .build();
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        return response.statusCode() == 200;
                    } catch (IOException | InterruptedException ex) {
                        return false;
                    }
                });
    }

    private void startSpringAiServer() {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("server.port", "0"),
                Map.entry("spring.main.web-application-type", "servlet"),
                Map.entry("spring.ai.mcp.server.enabled", "true"),
                Map.entry("spring.ai.mcp.server.protocol", "SSE"),
                Map.entry("spring.ai.mcp.server.name", "test-sse"),
                Map.entry("spring.ai.mcp.server.version", "1.0.0-e2e"),
                Map.entry("spring.ai.mcp.server.capabilities.resource", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.prompt", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.completion", "false"),
                Map.entry("spring.ai.mcp.server.tool-change-notification", "false"),
                Map.entry("spring.ai.mcp.server.prompt-change-notification", "false"),
                Map.entry("spring.ai.mcp.server.resource-change-notification", "false"),
                Map.entry("spring.ai.mcp.server.sse-endpoint", "/sse"),
                Map.entry("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message")
        );

        mcpServerContext = new SpringApplicationBuilder(TestMcpServerApplication.class)
                .properties(properties)
                .web(WebApplicationType.SERVLET)
                .run();
    }

    private String getSpringAiBaseUrl() {
        Objects.requireNonNull(mcpServerContext, "Spring AI server not started");
        String port = Objects.requireNonNull(mcpServerContext.getEnvironment().getProperty("local.server.port"));
        return "http://localhost:" + port + "/sse";
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void runCommand(Path workingDir, Duration timeout, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "Command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    String.format(Locale.ROOT, "Command '%s' failed with exit code %d and output:%n%s",
                            String.join(" ", command), process.exitValue(), output));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(TestMcpServerTools.class)
    static class TestMcpServerApplication {
    }

    @Component
    static class TestMcpServerTools {

        @McpTool(name = "echo", description = "Echo the provided text")
        public String echo(@McpToolParam(description = "Text to echo", required = true) String text) {
            return text;
        }
    }
}
