package app.application.service;

import app.domain.model.ClaimDecisionRequest;
import app.domain.model.ClaimDecisionResult;
import app.domain.service.ClaimDecisionCalculator;
import app.infrastructure.service.AuditDiaryManager;
import app.utilities.AppLogger;
import app.integrations.CacheService;
import app.integrations.ObjectStorageService;
import app.integrations.TabularDataService;
import app.integrations.SecretService;
import app.config.AppConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

/**
 * Application facade for Internal Case Management:calculation:decision.
 * Enforces thread safety via stateless design, structured logging, input validation,
 * and secure infra integration per NFRs.
 */
public final class ClaimDecisionFacade {

    private final ClaimDecisionCalculator calculator;
    private final CacheService cacheService;
    private final AuditDiaryManager auditDiaryManager;
    private final ObjectStorageService secureStorage;
    private final TabularDataService centralStore;
    private final String cachePrefix;
    private final String storagePrefix;

    public ClaimDecisionFacade(
            ClaimDecisionCalculator calculator,
            CacheService cacheService,
            AuditDiaryManager auditDiaryManager,
            ObjectStorageService secureStorage,
            TabularDataService centralStore) {
        this.calculator = Objects.requireNonNull(calculator, "Calculator must not be null");
        this.cacheService = Objects.requireNonNull(cacheService, "CacheService must not be null");
        this.auditDiaryManager = Objects.requireNonNull(auditDiaryManager, "AuditDiaryManager must not be null");
        this.secureStorage = Objects.requireNonNull(secureStorage, "SecureStorage must not be null");
        this.centralStore = Objects.requireNonNull(centralStore, "CentralStore must not be null");
        this.cachePrefix = AppConfig.get("CLAIM_DECISION_CACHE_PREFIX", "fnol:decision:");
        this.storagePrefix = AppConfig.get("CLAIM_DECISION_STORAGE_PREFIX", "Secure_Storage/claims/");
    }

    /**
     * Orchestrates claim calculation, decision, audit logging, diary creation, and persistence.
     * Thread-safe: stateless, immutable inputs/outputs, no shared mutable state.
     */
    public ClaimDecisionResult processDecision(ClaimDecisionRequest request) {
        validateRequest(request);
        String traceId = UUID.randomUUID().toString();
        String logContext = String.format(
            "[tenant=%s][claim=%s][policy=%s][actor=%s][role=%s][trace=%s]",
            request.getTenantId(), request.getClaimNumber(), request.getPolicyNumber(),
            request.getActor(), request.getRole(), traceId
        );

        // Idempotency check via cache (HA multi-AZ compatible)
        String cacheKey = cachePrefix + request.getClaimNumber();
        String cachedResult = cacheService.get(cacheKey);
        if (cachedResult != null && !cachedResult.isBlank()) {
            AppLogger.info(logContext + " - Cache hit. Returning cached decision: " + cachedResult);
            return new ClaimDecisionResult(request.getClaimNumber(), cachedResult, 0.0, Instant.now());
        }

        AppLogger.info(logContext + " - Processing claim decision.");

        // Domain calculation (stateless, thread-safe)
        ClaimDecisionResult result = calculator.calculate(request);
        result = new ClaimDecisionResult(request.getClaimNumber(), result.getDecisionCode(), result.getCalculatedAmount(), Instant.now());

        // NFR: structured_logging & nfr_section
        auditDiaryManager.logAudit(
            request.getTenantId(), request.getClaimNumber(), request.getPolicyNumber(),
            request.getActor(), request.getRole(), "CLAIM_DECISION_CALCULATED", result.getTimestamp()
        );
        auditDiaryManager.createStatutoryDiary(request.getClaimNumber(), "CLAIM_ACKNOWLEDGMENT_DUE");
        auditDiaryManager.createOperationalDiary(request.getClaimNumber(), "INVESTIGATION_START_DUE");

        // NFR: gdpr, soc2, tls_in_transit, least_privilege_iam
        Map<String, Object> itemPayload = buildItemPayload(request, result);
        centralStore.putItem(itemPayload);
        Path tempPath = Path.of("/tmp/decision_" + request.getClaimNumber() + ".json");
        secureStorage.upload(tempPath, storagePrefix + request.getClaimNumber() + ".json");

        // Cache result for idempotency
        cacheService.set(cacheKey, result.getDecisionCode());

        AppLogger.info(logContext + " - Decision processed successfully: " + result.getDecisionCode());
        return result;
    }

    private void validateRequest(ClaimDecisionRequest request) {
        if (request == null) throw new IllegalArgumentException("Request cannot be null");
        if (request.getTenantId() == null || request.getTenantId().isBlank()) throw new IllegalArgumentException("Tenant ID is required");
        if (request.getClaimNumber() == null || request.getClaimNumber().isBlank()) throw new IllegalArgumentException("Claim number is required");
        if (request.getPolicyNumber() == null || request.getPolicyNumber().isBlank()) throw new IllegalArgumentException("Policy number is required");
        if (request.getPayload() == null) throw new IllegalArgumentException("Payload is required");
        // Input sanitization/validation rules enforced at boundary per security NFR
    }

    private Map<String, Object> buildItemPayload(ClaimDecisionRequest request, ClaimDecisionResult result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", request.getTenantId());
        payload.put("claimNumber", request.getClaimNumber());
        payload.put("policyNumber", request.getPolicyNumber());
        payload.put("decisionCode", result.getDecisionCode());
        payload.put("calculatedAmount", result.getCalculatedAmount());
        payload.put("timestamp", result.getTimestamp().toString());
        payload.put("actor", request.getActor());
        payload.put("role", request.getRole());
        payload.put("payload", request.getPayload());
        return payload;
    }
}

package app.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable request DTO. Supports thread safety and minimal data footprint (GDPR).
 */
public final class ClaimDecisionRequest {
    private final String tenantId;
    private final String claimNumber;
    private final String policyNumber;
    private final String actor;
    private final String role;
    private final Map<String, Object> payload;

    public ClaimDecisionRequest(String tenantId, String claimNumber, String policyNumber, String actor, String role, Map<String, Object> payload) {
        this.tenantId = tenantId;
        this.claimNumber = claimNumber;
        this.policyNumber = policyNumber;
        this.actor = actor;
        this.role = role;
        this.payload = Map.copyOf(payload);
    }

    public String getTenantId() { return tenantId; }
    public String getClaimNumber() { return claimNumber; }
    public String getPolicyNumber() { return policyNumber; }
    public String getActor() { return actor; }
    public String getRole() { return role; }
    public Map<String, Object> getPayload() { return payload; }
}

/**
 * Immutable result DTO.
 */
public final class ClaimDecisionResult {
    private final String claimNumber;
    private final String decisionCode;
    private final double calculatedAmount;
    private final Instant timestamp;

    public ClaimDecisionResult(String claimNumber, String decisionCode, double calculatedAmount, Instant timestamp) {
        this.claimNumber = claimNumber;
        this.decisionCode = decisionCode;
        this.calculatedAmount = calculatedAmount;
        this.timestamp = timestamp;
    }

    public String getClaimNumber() { return claimNumber; }
    public String getDecisionCode() { return decisionCode; }
    public double getCalculatedAmount() { return calculatedAmount; }
    public Instant getTimestamp() { return timestamp; }
}

package app.domain.service;

import app.domain.model.ClaimDecisionRequest;
import app.domain.model.ClaimDecisionResult;
import java.time.Instant;

/**
 * Stateless domain calculator. Thread-safe by design.
 */
public final class ClaimDecisionCalculator {

    public ClaimDecisionResult calculate(ClaimDecisionRequest request) {
        Map<String, Object> payload = request.getPayload();
        double baseAmount = Double.parseDouble(String.valueOf(payload.getOrDefault("baseAmount", 0.0)));
        double deductible = Double.parseDouble(String.valueOf(payload.getOrDefault("deductible", 0.0)));
        double coverageLimit = Double.parseDouble(String.valueOf(payload.getOrDefault("coverageLimit", 100000.0)));
        String coverageType = String.valueOf(payload.getOrDefault("coverageType", "STANDARD"));

        double netAmount = Math.max(0.0, Math.min(baseAmount - deductible, coverageLimit));
        String decisionCode;

        if (netAmount <= 0.0) {
            decisionCode = "REJECTED";
        } else if ("EXCLUDED".equalsIgnoreCase(coverageType)) {
            decisionCode = "REJECTED_EXCLUDED";
        } else {
            decisionCode = "APPROVED";
        }

        return new ClaimDecisionResult(request.getClaimNumber(), decisionCode, netAmount, Instant.now());
    }
}

package app.infrastructure.service;

import app.integrations.TabularDataService;
import app.integrations.SecretService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Infrastructure adapter for Audit Diary Manager (DynamoDB).
 * Resolves table names via secrets management per security NFR.
 */
public final class AuditDiaryManager {

    private final TabularDataService tabularService;
    private final String tableName;
    private final String partitionKey;

    public AuditDiaryManager(TabularDataService tabularService, SecretService secretService) {
        this.tabularService = Objects.requireNonNull(tabularService);
        this.tableName = Objects.requireNonNull(secretService.resolve("AUDIT_DIARY_TABLE_NAME"), "Audit diary table name required");
        this.partitionKey = Objects.requireNonNull(secretService.resolve("AUDIT_DIARY_PK"), "Audit diary partition key required");
    }

    public void logAudit(String tenantId, String claimNumber, String policyNumber, String actor, String role, String action, Instant timestamp) {
        Map<String, Object> item = new HashMap<>();
        item.put("tenantId", tenantId);
        item.put("claimNumber", claimNumber);
        item.put("policyNumber", policyNumber);
        item.put("actor", actor);
        item.put("role", role);
        item.put("action", action);
        item.put("timestamp", timestamp.toString());
        item.put("pk", partitionKey + "#" + claimNumber);
        tabularService.putItem(item);
    }

    public void createStatutoryDiary(String claimNumber, String triggerEvent) {
        Map<String, Object> diary = new HashMap<>();
        diary.put("entity", "STATUTORY_DIARY");
        diary.put("triggerEvent", triggerEvent);
        diary.put("claimNumber", claimNumber);
        diary.put("dueDate", Instant.now().plusSeconds(86400).toString()); // Example: 24h from trigger
        diary.put("pk", partitionKey + "#DIARY#" + claimNumber);
        tabularService.putItem(diary);
    }

    public void createOperationalDiary(String claimNumber, String triggerEvent) {
        Map<String, Object> diary = new HashMap<>();
        diary.put("entity", "OPERATIONAL_DIARY");
        diary.put("triggerEvent", triggerEvent);
        diary.put("claimNumber", claimNumber);
        diary.put("dueDate", Instant.now().plusSeconds(172800).toString()); // Example: 48h from trigger
        diary.put("pk", partitionKey + "#DIARY#" + claimNumber);
        tabularService.putItem(diary);
    }
}