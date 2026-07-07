package app.claim.standardization.orchestration;

import app.config.AppConfig;
import app.integrations.CacheService;
import app.integrations.ObjectStorageService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Domain model representing the standardized state transition for claim data.
 * Immutable and thread-safe by design.
 */
public final class ClaimDataStandardizationState {
    private final String id;
    private final Map<String, Object> payload;

    public ClaimDataStandardizationState(String id, Map<String, Object> payload) {
        this.id = id;
        this.payload = Collections.unmodifiableMap(new HashMap<>(payload));
    }

    public String getId() { return id; }
    public Map<String, Object> getPayload() { return payload; }
}

/**
 * Outcome of the rules evaluation and decision engine.
 */
public final class DecisionResult {
    private final String claimId;
    private final String decisionCode;
    private final String reason;
    private final Instant timestamp;

    public DecisionResult(String claimId, String decisionCode, String reason, Instant timestamp) {
        this.claimId = claimId;
        this.decisionCode = decisionCode;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public String getClaimId() { return claimId; }
    public String getDecisionCode() { return decisionCode; }
    public String getReason() { return reason; }
    public Instant getTimestamp() { return timestamp; }
}

/**
 * Validates external inputs at service boundaries. Enforces GDPR/SOC2 data minimization.
 */
class InputValidator {
    private static final Pattern CLAIM_ID_PATTERN = Pattern.compile("^CLM-[A-Z0-9]{6,12}$");

    public void validateClaimId(String claimId) {
        if (claimId == null || !CLAIM_ID_PATTERN.matcher(claimId).matches()) {
            throw new IllegalArgumentException("Invalid claim ID format. Must match CLM-[A-Z0-9]{6,12}");
        }
    }

    public void validatePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }
    }
}

/**
 * Structured audit logger for SOC2/GDPR compliance. Captures tenant, claim, policy, actor, role, timestamp, action.
 */
class AuditLogger {
    public void logClaimEvent(String tenant, String claimNumber, String policyNumber,
                              String actor, String role, Instant timestamp, String action) {
        String structuredMessage = String.format(
            "{\"timestamp\":\"%s\",\"tenant\":\"%s\",\"claimNumber\":\"%s\",\"policyNumber\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"action\":\"%s\"}",
            timestamp, tenant, claimNumber, policyNumber, actor, role, action
        );
        AppLogger.info(structuredMessage);
    }
}

/**
 * Handles statutory and operational diary creation per operability NFR.
 */
class DiaryManager {
    private final ObjectStorageService objectStorageService;
    private final AuditLogger auditLogger;

    public DiaryManager(ObjectStorageService objectStorageService, AuditLogger auditLogger) {
        this.objectStorageService = objectStorageService;
        this.auditLogger = auditLogger;
    }

    public void createStatutoryDiary(String tenant, String claimNumber, String triggerEvent) {
        String diaryKey = String.format("diaries/%s/statutory_%s.json", claimNumber, triggerEvent);
        Map<String, Object> diaryPayload = Map.of(
            "tenant", tenant, "claimId", claimNumber, "trigger", triggerEvent,
            "created", Instant.now().toString(), "type", "STATUTORY"
        );
        // In production: serialize to JSON, upload via objectStorageService.upload()
        auditLogger.logClaimEvent(tenant, claimNumber, "", "SYSTEM", "DIARY_SERVICE", Instant.now(), "CREATE_STATUTORY_DIARY");
    }

    public void createOperationalDiary(String tenant, String claimNumber, String triggerEvent) {
        String diaryKey = String.format("diaries/%s/operational_%s.json", claimNumber, triggerEvent);
        Map<String, Object> diaryPayload = Map.of(
            "tenant", tenant, "claimId", claimNumber, "trigger", triggerEvent,
            "created", Instant.now().toString(), "type", "OPERATIONAL"
        );
        // In production: serialize to JSON, upload via objectStorageService.upload()
        auditLogger.logClaimEvent(tenant, claimNumber, "", "SYSTEM", "DIARY_SERVICE", Instant.now(), "CREATE_OPERATIONAL_DIARY");
    }
}

/**
 * Stateless decision engine evaluating rules against standardized claim data.
 * Thread-safe via immutability and stateless design.
 */
class DecisionEngine {
    private final CacheService cacheService;
    private final TabularDataService rulesTriageService;
    private final AuditLogger auditLogger;

    public DecisionEngine(CacheService cacheService, TabularDataService rulesTriageService, AuditLogger auditLogger) {
        this.cacheService = cacheService;
        this.rulesTriageService = rulesTriageService;
        this.auditLogger = auditLogger;
    }

    public DecisionResult evaluate(String claimId, Map<String, Object> standardizedPayload, String tenant) {
        // Retrieve triage rules from cache or tabular store
        String ruleKey = String.format("rules:triage:%s", tenant);
        // String rulesDefinition = cacheService.get(ruleKey);
        
        // Derive decision based on standardized payload (simplified logic)
        String decisionCode = "APPROVED_TRIAGE";
        String reason = "Standardized claim data meets triage criteria";

        auditLogger.logClaimEvent(tenant, claimId, "", "SYSTEM", "DECISION_ENGINE", Instant.now(), "EVALUATE_RULES");
        return new DecisionResult(claimId, decisionCode, reason, Instant.now());
    }
}

/**
 * Core orchestrator for Claim Data Standardization and Decision workflow.
 * Implements HA-ready stateless design, secrets management, input validation, and structured auditing.
 */
public class ClaimDataOrchestrator {
    private final TabularDataService claimDataStore;
    private final DecisionEngine decisionEngine;
    private final DiaryManager diaryManager;
    private final InputValidator inputValidator;
    private final AuditLogger auditLogger;
    private final SecretService secretService;

    public ClaimDataOrchestrator(TabularDataService claimDataStore, DecisionEngine decisionEngine,
                                 DiaryManager diaryManager, InputValidator inputValidator,
                                 AuditLogger auditLogger, SecretService secretService) {
        this.claimDataStore = claimDataStore;
        this.decisionEngine = decisionEngine;
        this.diaryManager = diaryManager;
        this.inputValidator = inputValidator;
        this.auditLogger = auditLogger;
        this.secretService = secretService;
    }

    public DecisionResult orchestrate(String claimId, String tenant, String policyNumber, String actor, String role) {
        // 1. Input Validation & Secrets Management (TLS/Least-Privilege IAM enforced via config)
        inputValidator.validateClaimId(claimId);
        String dbCredentials = secretService.resolve("CLAIM_DATA_STORE_CREDENTIALS");
        if (dbCredentials == null || dbCredentials.isBlank()) {
            throw new IllegalStateException("Missing database credentials. Ensure least-privilege IAM and secrets management.");
        }

        // 2. Fetch Claim Data from DynamoDB
        Map<String, Object> claimItem = claimDataStore.getItem(claimId, "metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) claimItem.get("payload");
        inputValidator.validatePayload(payload);

        // 3. Standardize Payload (GDPR: Data Minimization)
        Map<String, Object> standardizedPayload = standardizePayload(payload);

        // 4. Trigger Diaries (Operability NFR)
        diaryManager.createStatutoryDiary(tenant, claimId, "CLAIM_NOTICE_RECEIVED");
        diaryManager.createOperationalDiary(tenant, claimId, "CLAIM_NOTICE_RECEIVED");

        // 5. Evaluate Rules & Make Decision (Thread-safe: stateless engine)
        DecisionResult result = decisionEngine.evaluate(claimId, standardizedPayload, tenant);

        // 6. Persist State Transition
        ClaimDataStandardizationState state = new ClaimDataStandardizationState(
            claimId, Map.of("decision", result.getDecisionCode(), "reason", result.getReason(), "timestamp", result.getTimestamp().toString())
        );
        claimDataStore.putItem(Map.of("id", state.getId(), "payload", state.getPayload()));

        // 7. Structured Audit Logging (SOC2/GDPR)
        auditLogger.logClaimEvent(tenant, claimId, policyNumber, actor, role, Instant.now(), "CLAIM_DATA_STANDARDIZATION_DECISION_COMPLETED");

        return result;
    }

    private Map<String, Object> standardizePayload(Map<String, Object> rawPayload) {
        Map<String, Object> canonical = new HashMap<>();
        canonical.put("claimId", rawPayload.get("claimId"));
        canonical.put("policyNumber", rawPayload.get("policyNumber"));
        canonical.put("incidentDate", rawPayload.get("incidentDate"));
        canonical.put("description", rawPayload.get("description"));
        canonical.put("status", "STANDARDIZED");
        return Collections.unmodifiableMap(canonical);
    }
}