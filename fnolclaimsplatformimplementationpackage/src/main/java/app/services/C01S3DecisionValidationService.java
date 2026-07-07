package app.claiminitiation;

import app.config.AppConfig;
import app.integrations.CacheService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Layer: Domain Model
 * Immutable request DTO for Claim Initiation & Routing:decision:validation
 */
class ClaimInitiationRequest {
    private final String id;
    private final Map<String, Object> payload;
    private final String tenant;
    private final String actor;
    private final String role;

    public ClaimInitiationRequest(String id, Map<String, Object> payload, String tenant, String actor, String role) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id must not be null");
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        this.id = id.trim();
        this.payload = Collections.unmodifiableMap(new HashMap<>(payload));
        this.tenant = tenant != null ? tenant : "DEFAULT";
        this.actor = actor != null ? actor : "SYSTEM";
        this.role = role != null ? role : "FNOL_PROCESSOR";
    }

    public String getId() { return id; }
    public Map<String, Object> getPayload() { return payload; }
    public String getTenant() { return tenant; }
    public String getActor() { return actor; }
    public String getRole() { return role; }
}

/**
 * Layer: Domain Model
 * Immutable result DTO for validation and routing decision
 */
class ValidationResult {
    private final String id;
    private final boolean valid;
    private final String routingDecision;
    private final List<String> errors;
    private final Instant timestamp;

    public ValidationResult(String id, boolean valid, String routingDecision, List<String> errors) {
        this.id = id;
        this.valid = valid;
        this.routingDecision = routingDecision != null ? routingDecision : "UNROUTED";
        this.errors = errors != null ? Collections.unmodifiableList(new ArrayList<>(errors)) : Collections.emptyList();
        this.timestamp = Instant.now();
    }

    public String getId() { return id; }
    public boolean isValid() { return valid; }
    public String getRoutingDecision() { return routingDecision; }
    public List<String> getErrors() { return errors; }
    public Instant getTimestamp() { return timestamp; }
}

/**
 * Layer: Application Service
 * Stateless, thread-safe validator and router for FNOL claims.
 * Enforces input validation, structured audit logging, and secure infra access.
 */
public class ClaimValidationService {

    private final CacheService cacheService;
    private final TabularDataService tabularDataService;
    private final SecretService secretService;
    private final String tableName;
    private final String partitionKey;
    private final String cacheKeyNamespace;
    private final int ttlSeconds;
    private final boolean tlsEnforced;

    // Layer: Infrastructure Config
    public ClaimValidationService(CacheService cacheService, TabularDataService tabularDataService, SecretService secretService) {
        this.cacheService = cacheService;
        this.tabularDataService = tabularDataService;
        this.secretService = secretService;
        this.tableName = AppConfig.get("TABULAR_TABLE_NAME", "Claims & Policy Data Store_table");
        this.partitionKey = AppConfig.get("TABULAR_PARTITION_KEY", "pk");
        this.cacheKeyNamespace = AppConfig.get("REDIS_CACHE_KEY_NAMESPACE", "Cache & Reference Data:cache:");
        this.ttlSeconds = Integer.parseInt(AppConfig.get("REDIS_TTL_SECONDS", "3600"));
        this.tlsEnforced = Boolean.parseBoolean(AppConfig.get("TLS_ENFORCED", "true"));
        validateInfraSecurity();
    }

    /**
     * Executes validation, routing decision, and persists result.
     * Thread-safe: stateless, immutable inputs/outputs, concurrent-safe logging.
     */
    public ValidationResult process(ClaimInitiationRequest request) {
        List<String> errors = new ArrayList<>();
        Instant start = Instant.now();
        String claimNumber = request.getId();
        String policyNumber = (String) request.getPayload().getOrDefault("policyNumber", "UNKNOWN");

        // Structured audit log: FNOL initiation start
        AppLogger.info(structuredLog(request.getTenant(), claimNumber, policyNumber, request.getActor(), request.getRole(), "FNOL_INITIATION_START", start.toString()));

        // 1. Input Validation
        errors.addAll(validateInput(request));
        if (!errors.isEmpty()) {
            logAudit(request, claimNumber, policyNumber, "FNOL_VALIDATION_FAILED", start);
            return new ValidationResult(claimNumber, false, "REJECTED", errors);
        }

        // 2. Reference Data & Policy Lookup (Thread-safe via stateless cache/db calls)
        String policyStatus = resolvePolicyStatus(policyNumber);
        if (!"ACTIVE".equalsIgnoreCase(policyStatus)) {
            errors.add("Policy is not active or not found: " + policyNumber);
        }

        // 3. Routing Decision
        String claimType = (String) request.getPayload().getOrDefault("claimType", "GENERAL");
        String decision = computeRoutingDecision(claimType, policyStatus);

        // 4. Persist Validation Result & Create Statutory Diary
        persistResult(request, decision);
        createDiaryEvent(request, claimNumber, policyNumber, decision);

        // 5. Structured audit log: FNOL completion
        logAudit(request, claimNumber, policyNumber, "FNOL_VALIDATION_COMPLETE", start);

        return new ValidationResult(claimNumber, errors.isEmpty(), decision, errors);
    }

    private List<String> validateInput(ClaimInitiationRequest request) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> payload = request.getPayload();
        if (!payload.containsKey("policyNumber")) errors.add("Missing required field: policyNumber");
        if (!payload.containsKey("incidentDate")) errors.add("Missing required field: incidentDate");
        if (!payload.containsKey("claimType")) errors.add("Missing required field: claimType");
        if (!payload.containsKey("description")) errors.add("Missing required field: description");
        // GDPR: Data minimization check - reject excessive PII if config restricts
        if (Boolean.parseBoolean(AppConfig.get("GDPR_DATA_MINIMIZATION", "true")) && payload.size() > 10) {
            errors.add("Payload exceeds data minimization threshold");
        }
        return errors;
    }

    private String resolvePolicyStatus(String policyNumber) {
        String cacheKey = cacheKeyNamespace + "policy:" + policyNumber;
        String cached = cacheService.get(cacheKey);
        if (cached != null) return cached;
        Map<String, Object> item = tabularDataService.getItem(partitionKey, "POLICY:" + policyNumber);
        if (item == null) return "UNKNOWN";
        String status = (String) item.get("status");
        if (status != null) cacheService.set(cacheKey, status);
        return status != null ? status : "UNKNOWN";
    }

    private String computeRoutingDecision(String claimType, String policyStatus) {
        if (!"ACTIVE".equalsIgnoreCase(policyStatus)) return "QUARANTINE";
        if ("AUTO".equalsIgnoreCase(claimType)) return "FAST_TRACK_AUTO";
        if ("PROPERTY".equalsIgnoreCase(claimType)) return "PROPERTY_ASSESSOR_QUEUE";
        return "GENERAL_INVESTIGATION_QUEUE";
    }

    private void persistResult(ClaimInitiationRequest request, String decision) {
        Map<String, Object> item = new HashMap<>();
        item.put(partitionKey, "VALIDATION:" + request.getId());
        item.put("id", request.getId());
        item.put("payload", request.getPayload());
        item.put("routingDecision", decision);
        item.put("validatedAt", Instant.now().toString());
        tabularDataService.putItem(item);
    }

    private void createDiaryEvent(ClaimInitiationRequest request, String claimNumber, String policyNumber, String decision) {
        // Operability: NFR section - Statutory/Operational diaries
        AppLogger.info(structuredLog(request.getTenant(), claimNumber, policyNumber, request.getActor(), request.getRole(), "DIARY_CREATED_ACKNOWLEDGMENT", Instant.now().toString()));
        AppLogger.info(structuredLog(request.getTenant(), claimNumber, policyNumber, request.getActor(), request.getRole(), "DIARY_CREATED_INVESTIGATION_START", Instant.now().toString()));
    }

    private void logAudit(ClaimInitiationRequest request, String claimNumber, String policyNumber, String action, Instant start) {
        AppLogger.info(structuredLog(request.getTenant(), claimNumber, policyNumber, request.getActor(), request.getRole(), action, Instant.now().toString()));
    }

    private String structuredLog(String tenant, String claimNumber, String policyNumber, String actor, String role, String action, String timestamp) {
        return String.format(
            "{\"tenant\":\"%s\",\"claimNumber\":\"%s\",\"policyNumber\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"timestamp\":\"%s\",\"action\":\"%s\",\"platform\":\"FNOL_Claims_Platform\"}",
            tenant, claimNumber, policyNumber, actor, role, timestamp, action
        );
    }

    private void validateInfraSecurity() {
        if (!tlsEnforced) {
            throw new SecurityException("TLS enforcement is required for transit security. Set TLS_ENFORCED=true");
        }
        String secret = secretService.resolve("FNOL_DB_CREDENTIALS");
        if (secret == null || secret.isBlank()) {
            throw new SecurityException("Least-privilege IAM/Secrets: FNOL_DB_CREDENTIALS not resolved");
        }
    }
}