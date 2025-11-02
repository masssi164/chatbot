package app.chatbot.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template Method Pattern for connection verification.
 * <p>
 * Provides a standardized flow for verifying external connections:
 * <ol>
 *   <li>Pre-condition check (e.g., configuration validation)</li>
 *   <li>Execution of connection test</li>
 *   <li>Exception handling and error reporting</li>
 *   <li>Construction of result response</li>
 * </ol>
 * <p>
 * This abstract class follows the Template Method design pattern, defining
 * the skeleton of the verification algorithm while delegating specific steps
 * to subclasses.
 *
 * @param <T> The response type returned by the verification process
 */
public abstract class ConnectionVerificationTemplate<T> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Template method defining the connection verification flow.
     * <p>
     * This method orchestrates the verification process and should NOT be overridden.
     * Subclasses customize behavior by implementing the abstract hook methods.
     *
     * @return The verification result
     */
    public final T verify() {
        try {
            // Step 1: Validate pre-conditions
            ValidationResult validation = validatePreConditions();
            if (!validation.isValid()) {
                logger.warn("Pre-condition validation failed: {}", validation.message());
                return buildFailureResponse(validation.message());
            }

            // Step 2: Perform the actual connection test
            logger.debug("Performing connection test for {}", getServiceName());
            performConnectionTest();

            // Step 3: Build success response
            logger.info("Connection test successful for {}", getServiceName());
            return buildSuccessResponse();

        } catch (Exception ex) {
            logger.error("Connection test failed for {}: {}", getServiceName(), ex.getMessage(), ex);
            return buildFailureResponse(ex.getMessage());
        }
    }

    /**
     * Validates pre-conditions before attempting connection.
     * <p>
     * Examples: configuration present, required parameters not null, etc.
     *
     * @return ValidationResult indicating whether to proceed
     */
    protected abstract ValidationResult validatePreConditions();

    /**
     * Executes the actual connection test.
     * <p>
     * This method should perform a lightweight operation to verify connectivity,
     * such as listing resources with a limit of 1, or performing a health check.
     * <p>
     * Implementations should throw meaningful exceptions on failure.
     *
     * @throws Exception if the connection test fails
     */
    protected abstract void performConnectionTest() throws Exception;

    /**
     * Constructs a success response after successful connection test.
     *
     * @return The success response
     */
    protected abstract T buildSuccessResponse();

    /**
     * Constructs a failure response with the given error message.
     *
     * @param errorMessage The error message describing the failure
     * @return The failure response
     */
    protected abstract T buildFailureResponse(String errorMessage);

    /**
     * Returns the service name for logging purposes.
     *
     * @return The service name (e.g., "n8n", "MCP Server")
     */
    protected abstract String getServiceName();

    /**
     * Represents the result of pre-condition validation.
     *
     * @param isValid Whether validation passed
     * @param message Optional message explaining validation failure
     */
    protected record ValidationResult(boolean isValid, String message) {

        /**
         * Creates a successful validation result.
         */
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        /**
         * Creates a failed validation result with an error message.
         *
         * @param message The validation error message
         */
        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}
