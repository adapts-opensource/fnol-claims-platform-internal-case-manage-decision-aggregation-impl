package app.claimdata.standardization.decision.validation;

import app.config.*;
import app.integrations.*;
import app.utilities.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable domain entity for Claim Data Standardization:decision:validation.
 * Thread-safe by design (immutable state, unmodifiable collections).
 */
public final class ClaimDataStandardizationDecisionValidation {
    private final String id;
    private final Map<String, Object> payload;

    public ClaimDataStandardizationDecisionValidation(String id, Map<String, Object> payload) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Entity id must not be null or blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        this.id = id;
        this.payload = Collections.unmodifiableMap(new HashMap<>(payload));
    }

    public String getId() { return id; }
    public Map<String, Object> getPayload() { return payload; }
}

/**
 * Repository layer: Persists and retrieves validation records to DynamoDB.
 * Uses constructor injection for infrastructure dependency.
 */
public class ClaimDataStandardizationDecisionValidationRepository {
    private final TabularDataService tabularDataService;
    private final String tableName;
    private final String partitionKey;

    public ClaimDataStandardizationDecisionValidationRepository(TabularDataService tabularDataService) {
        this.tabularDataService = tabularDataService;
        this.tableName = AppConfig.get("DYNAMODB_TABLE_NAME", "Policy & Claim Data Store_table");
        this.partitionKey = AppConfig.get("DYNAMODB_PARTITION_KEY", "pk");
    }

    public void save(ClaimDataStandardizationDecisionValidation entity) {
        Map<String, Object> item = new HashMap<>();
        item.put(partitionKey, entity.getId());
        item.put("payload", entity.getPayload());
        item.put("created_at", Instant.now().toString());
        tabularDataService.putItem(item);
    }

    public ClaimDataStandardizationDecisionValidation findById(String id) {
        Map<String, Object> item = tabularDataService.getItem(partitionKey, id);
        if (item == null || item.isEmpty()) return null;
        return new ClaimDataStandardizationDecisionValidation(
            (String) item.get(partitionKey),
            (Map<String, Object>) item.get("payload")
        );
    }
}

/**
 * Validation & Standardization layer: Enforces business rules, sanitizes input,
 * and normalizes payload structure. Thread-safe (stateless, pure functions).
 */
public class ClaimDataStandardizationDecisionValidationValidator {
    private static final Set<String> REQUIRED_KEYS = Set.of("claim_number", "policy_number", "decision_code", "validation_status");

    public Map<String, Object> validateAndStandardize(Map<String, Object> rawPayload) {
        if (rawPayload == null) {
            throw new IllegalArgumentException("Raw payload cannot be null");
        }

        Map<String, Object> standardized = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawPayload.entrySet()) {
            String key = entry.getKey().trim().toLowerCase().replace(" ", "_");
            Object value = entry.getValue();

            if (value instanceof String) {
                value = ((String) value).trim();
                value = sanitizeInput((String) value);
            }
            standardized.put(key, value);
        }

        // GDPR/SOC2: Enforce data minimization & required field presence
        for (String reqKey : REQUIRED_KEYS) {
            if (!standardized.containsKey(reqKey)) {
                throw new IllegalArgumentException("Missing required field for validation: " + reqKey);
            }
        }

        // Validate claim/policy formats
        String claimNumber = String.valueOf(standardized.get("claim_number"));
        String policyNumber = String.valueOf(standardized.get("policy_number"));
        if (!Constants.CLAIM_PATTERN.matcher(claimNumber).matches()) {
            throw new IllegalArgumentException("Invalid claim number format");
        }
        if (!Constants.POLICY_PATTERN.matcher(policyNumber).matches()) {
            throw new IllegalArgumentException("Invalid policy number format");
        }

        // Standardize decision code to uppercase
        String decisionCode = String.valueOf(standardized.get("decision_code")).toUpperCase();
        standardized.put("decision_code", decisionCode);

        // Ensure audit timestamp
        if (!standardized.containsKey("validation_timestamp")) {
            standardized.put("validation_timestamp", Instant.now().toString());
        }

        return standardized;
    }

    /**
     * Sanitizes input to prevent injection and enforce clean data.
     */
    private String sanitizeInput(String input) {
        return input.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
    }
}

/**
 * Service layer: Orchestrates validation, persistence, and structured audit logging.
 * Constructor injection ensures testability and clean dependency graph.
 */
public class ClaimDataStandardizationDecisionValidationService {
    private final ClaimDataStandardizationDecisionValidationRepository repository;
    private final ClaimDataStandardizationDecisionValidationValidator validator;
    private final SecretService secretService;
    private final String tenant;
    private final String actor;
    private final String role;
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ISO_INSTANT;

    public ClaimDataStandardizationDecisionValidationService(
            ClaimDataStandardizationDecisionValidationRepository repository,
            ClaimDataStandardizationDecisionValidationValidator validator,
            SecretService secretService) {
        this.repository = repository;
        this.validator = validator;
        this.secretService = secretService;
        this.tenant = AppConfig.get("TENANT_ID", "NEWCO_INSURANCE");
        this.actor = AppConfig.get("SYSTEM_ACTOR", "FNOL_WORKER");
        this.role = AppConfig.get("SYSTEM_ROLE", "CLAIM_PROCESSOR");
        
        // Security: Least-privilege IAM & secrets management
        secretService.resolve("DYNAMODB_CREDENTIALS");
        secretService.resolve("OBJECT_STORAGE_CREDENTIALS");
    }

    public ClaimDataStandardizationDecisionValidation processDecisionValidation(String claimNumber, Map<String, Object> rawPayload) {
        String action = "CLAIM_DECISION_VALIDATION_PROCESS";
        String policyNumber = AppConfig.get("POLICY_NUMBER", "UNKNOWN");
        String logTs = Instant.now().format(LOG_TS);
        
        AppLogger.info(String.format(
            "{\"tenant\":\"%s\",\"claim_number\":\"%s\",\"policy_number\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"timestamp\":\"%s\",\"action\":\"%s\"}",
            tenant, claimNumber, policyNumber, actor, role, logTs, action
        ));

        try {
            Map<String, Object> standardizedPayload = validator.validateAndStandardize(rawPayload);
            String entityId = UUID.randomUUID().toString();
            ClaimDataStandardizationDecisionValidation entity = new ClaimDataStandardizationDecisionValidation(entityId, standardizedPayload);
            
            repository.save(entity);

            AppLogger.info(String.format(
                "{\"tenant\":\"%s\",\"claim_number\":\"%s\",\"policy_number\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"timestamp\":\"%s\",\"action\":\"%s\",\"status\":\"SUCCESS\"}",
                tenant, claimNumber, policyNumber, actor, role, Instant.now().format(LOG_TS), action
            ));
            return entity;
        } catch (Exception e) {
            AppLogger.info(String.format(
                "{\"tenant\":\"%s\",\"claim_number\":\"%s\",\"policy_number\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"timestamp\":\"%s\",\"action\":\"%s\",\"status\":\"FAILED\",\"error\":\"%s\"}",
                tenant, claimNumber, policyNumber, actor, role, Instant.now().format(LOG_TS), action, e.getMessage()
            ));
            throw e;
        }
    }
}