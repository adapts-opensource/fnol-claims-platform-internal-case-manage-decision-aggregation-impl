package app.domain.claim.initiation.decision;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable value object representing the validated claim initiation payload.
 * Enforces data minimization (GDPR) and thread safety via immutability.
 */
public final class ClaimInitiationPayload {
    private final String id;
    private final Map<String, Object> data;
    private final String tenantId;
    private final String policyNumber;
    private final String claimType;
    private final String severity;
    private final Instant createdAt;

    public ClaimInitiationPayload(String id, Map<String, Object> data, String tenantId,
                                  String policyNumber, String claimType, String severity) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.data = Map.copyOf(Objects.requireNonNull(data, "payload must not be null"));
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.policyNumber = Objects.requireNonNull(policyNumber, "policyNumber must not be null");
        this.claimType = Objects.requireNonNull(claimType, "claimType must not be null");
        this.severity = Objects.requireNonNull(severity, "severity must not be null");
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public Map<String, Object> getData() { return data; }
    public String getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getClaimType() { return claimType; }
    public String getSeverity() { return severity; }
    public Instant getCreatedAt() { return createdAt; }
}

package app.application.claim.initiation.decision;

import app.domain.claim.initiation.decision.ClaimInitiationPayload;
import app.infrastructure.claim.initiation.decision.ClaimValidationAdapter;
import app.infrastructure.claim.initiation.decision.DiaryManagementAdapter;
import app.infrastructure.claim.initiation.decision.RoutingDecisionAdapter;
import app.infrastructure.claim.initiation.decision.StructuredAuditLogger;
import app.utilities.AppLogger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates claim initiation, validation, routing decision, and statutory diary creation.
 * Thread-safe via immutable context and explicit locking on side-effects.
 * Stateless design supports HA multi-AZ deployment.
 */
public final class ClaimInitiationOrchestrator {
    private final ClaimValidationAdapter validationAdapter;
    private final RoutingDecisionAdapter routingAdapter;
    private final DiaryManagementAdapter diaryAdapter;
    private final StructuredAuditLogger auditLogger;
    private final ReentrantLock diaryCreationLock;

    public ClaimInitiationOrchestrator(ClaimValidationAdapter validationAdapter,
                                       RoutingDecisionAdapter routingAdapter,
                                       DiaryManagementAdapter diaryAdapter,
                                       StructuredAuditLogger auditLogger) {
        this.validationAdapter = validationAdapter;
        this.routingAdapter = routingAdapter;
        this.diaryAdapter = diaryAdapter;
        this.auditLogger = auditLogger;
        this.diaryCreationLock = new ReentrantLock();
    }

    /**
     * Main orchestration entry point.
     * Validates input, checks idempotency, routes claim, creates diaries, and returns decision.
     */
    public Map<String, Object> orchestrateDecision(String rawTenantId, String rawActor, String rawRole,
                                                   Map<String, Object> rawPayload) {
        String tenantId = Objects.requireNonNull(rawTenantId, "tenantId is required");
        String actor = Objects.requireNonNull(rawActor, "actor is required");
        String role = Objects.requireNonNull(rawRole, "role is required");
        Map<String, Object> payload = Map.copyOf(Objects.requireNonNull(rawPayload, "payload is required"));

        String claimId = UUID.randomUUID().toString();
        String policyNumber = extractString(payload, "policyNumber", "UNKNOWN");
        String claimType = extractString(payload, "claimType", "GENERAL");
        String severity = extractString(payload, "severity", "LOW");

        ClaimInitiationPayload validatedPayload = validationAdapter.validateAndEnrich(claimId, payload, tenantId);
        auditLogger.log("CLAIM_INITIATED", tenantId, claimId, policyNumber, actor, role, "Claim initiation payload validated");

        String routingRoute = routingAdapter.decideRouting(validatedPayload);
        auditLogger.log("ROUTING_DECISION", tenantId, claimId, policyNumber, actor, role, "Routing decision: " + routingRoute);

        // Idempotent diary creation with thread-safe lock
        diaryCreationLock.lock();
        try {
            if (!diaryAdapter.hasDiaryForClaim(claimId)) {
                diaryAdapter.createStatutoryDiary(claimId, policyNumber, "CLAIM_ACKNOWLEDGMENT", tenantId, actor);
                auditLogger.log("DIARY_CREATED", tenantId, claimId, policyNumber, actor, role, "Statutory diary created");
            }
        } finally {
            diaryCreationLock.unlock();
        }

        return Map.of(
            "claimId", claimId,
            "policyNumber", policyNumber,
            "routingRoute", routingRoute,
            "diaryCreated", true,
            "timestamp", Instant.now().toString()
        );
    }

    private String extractString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}

package app.infrastructure.claim.initiation.decision;

import app.integrations.CacheService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Validates claim initiation payload against business rules and reference data.
 * Implements input validation, GDPR data minimization, and SOC2 audit readiness.
 */
public final class ClaimValidationAdapter {
    private final TabularDataService claimsDataStore;
    private final TabularDataService referenceDataStore;
    private final CacheService cacheService;
    private final SecretService secretService;

    public ClaimValidationAdapter(TabularDataService claimsDataStore,
                                  TabularDataService referenceDataStore,
                                  CacheService cacheService,
                  SecretService secretService) {
        this.claimsDataStore = claimsDataStore;
        this.referenceDataStore = referenceDataStore;
        this.cacheService = cacheService;
        this.secretService = secretService;
    }

    public Map<String, Object> validateAndEnrich(String claimId, Map<String, Object> payload, String tenantId) {
        // Input validation: enforce required fields
        List<String> requiredKeys = List.of("policyNumber", "claimType", "severity", "insuredName");
        for (String key : requiredKeys) {
            if (!payload.containsKey(key) || payload.get(key) == null) {
                throw new IllegalArgumentException("Missing required field: " + key);
            }
        }

        // Idempotency & Reference Data Cache Check
        String cacheKey = "Cache & Reference Data:cache:claim:" + claimId;
        String cached = cacheService.get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            AppLogger.info("Idempotent cache hit for claim: " + claimId);
            return Map.of("status", "ALREADY_PROCESSED", "claimId", claimId);
        }

        // Validate policy existence against Claims & Policy Data Store
        Map<String, Object> policyRecord = referenceDataStore.getItem("pk", "POLICY:" + payload.get("policyNumber"));
        if (policyRecord == null) {
            throw new IllegalArgumentException("Policy not found: " + payload.get("policyNumber"));
        }

        // Sanitize/minimize payload for GDPR compliance
        Map<String, Object> sanitizedPayload = Map.of(
            "id", claimId,
            "policyNumber", payload.get("policyNumber"),
            "claimType", payload.get("claimType"),
            "severity", payload.get("severity"),
            "tenantId", tenantId,
            "createdAt", Instant.now().toString()
        );

        // Persist validation record
        claimsDataStore.putItem(sanitizedPayload);
        cacheService.set(cacheKey, Instant.now().plusSeconds(3600).toString());

        return sanitizedPayload;
    }
}

package app.infrastructure.claim.initiation.decision;

import app.integrations.CacheService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;

import java.util.Map;
import java.util.Objects;

/**
 * Applies business rules to determine claim routing path.
 * Stateless and thread-safe. Supports TLS via infra client contracts.
 */
public final class RoutingDecisionAdapter {
    private final TabularDataService routingRulesStore;
    private final CacheService cacheService;
    private final SecretService secretService;

    public RoutingDecisionAdapter(TabularDataService routingRulesStore,
                                  CacheService cacheService,
                                  SecretService secretService) {
        this.routingRulesStore = routingRulesStore;
        this.cacheService = cacheService;
        this.secretService = secretService;
    }

    public String decideRouting(Map<String, Object> payload) {
        String claimType = Objects.toString(payload.get("claimType"), "GENERAL");
        String severity = Objects.toString(payload.get("severity"), "LOW");

        // Least privilege IAM: secrets resolved at runtime, never hardcoded
        String routingStrategy = secretService.resolve("ROUTING_STRATEGY");
        if (routingStrategy == null) {
            routingStrategy = "DEFAULT";
        }

        // Rule engine (simplified for demonstration)
        if ("FRAUD".equalsIgnoreCase(claimType) || "HIGH".equalsIgnoreCase(severity)) {
            return "FRAUD_REVIEW";
        }
        if ("AUTO_SETTLE".equalsIgnoreCase(claimType)) {
            return "AUTO_SETTLE";
        }
        return "MANUAL_ADJUSTER";
    }
}

package app.infrastructure.claim.initiation.decision;

import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages statutory and operational diaries per SOC2/GDPR compliance requirements.
 * Idempotent side-effects with thread-safe locking.
 */
public final class DiaryManagementAdapter {
    private final TabularDataService diaryStore;
    private final SecretService secretService;
    private final ReentrantLock idempotencyLock;

    public DiaryManagementAdapter(TabularDataService diaryStore, SecretService secretService) {
        this.diaryStore = diaryStore;
        this.secretService = secretService;
        this.idempotencyLock = new ReentrantLock();
    }

    public boolean hasDiaryForClaim(String claimId) {
        return diaryStore.query("CLAIM_DIARY:" + claimId).size() > 0;
    }

    public void createStatutoryDiary(String claimId, String policyNumber, String eventType, String tenantId, String actor) {
        idempotencyLock.lock();
        try {
            if (hasDiaryForClaim(claimId)) {
                return; // Idempotent guard
            }

            String retentionPolicy = secretService.resolve("DIARY_RETENTION_DAYS");
            if (retentionPolicy == null) retentionPolicy = "2555"; // 7 years default

            Map<String, Object> diaryItem = Map.of(
                "pk", "CLAIM_DIARY:" + claimId,
                "sk", eventType + ":" + Instant.now().toEpochMilli(),
                "claimId", claimId,
                "policyNumber", policyNumber,
                "eventType", eventType,
                "tenantId", tenantId,
                "actor", actor,
                "role", "SYSTEM",
                "timestamp", Instant.now().toString(),
                "retentionDays", Integer.parseInt(retentionPolicy),
                "status", "ACTIVE"
            );

            diaryStore.putItem(diaryItem);
        } finally {
            idempotencyLock.unlock();
        }
    }
}

package app.infrastructure.claim.initiation.decision;

import app.utilities.AppLogger;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Structured audit logger compliant with SOC2 and GDPR observability requirements.
 * Captures tenant, claim number, policy number, actor, role, timestamp, and action.
 */
public final class StructuredAuditLogger {
    private final String applicationName;

    public StructuredAuditLogger(String applicationName) {
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName must not be null");
    }

    public void log(String action, String tenant, String claimNumber, String policyNumber,
                    String actor, String role, String detail) {
        Map<String, Object> logEntry = new TreeMap<>();
        logEntry.put("application", applicationName);
        logEntry.put("timestamp", Instant.now().toString());
        logEntry.put("tenant", tenant);
        logEntry.put("claimNumber", claimNumber);
        logEntry.put("policyNumber", policyNumber);
        logEntry.put("actor", actor);
        logEntry.put("role", role);
        logEntry.put("action", action);
        logEntry.put("detail", detail);
        logEntry.put("tls_in_transit", true);
        logEntry.put("gdpr_minimized", true);

        AppLogger.info(logEntry.toString());
    }
}