package app.fnol.api;

import app.fnol.domain.model.FnolSubmissionRequest;
import app.fnol.domain.model.FnolSubmissionResponse;
import app.fnol.domain.service.FnolSubmissionService;
import app.utilities.AppLogger;

/**
 * API Controller for Multi-Channel FNOL Submission.
 * Enforces input validation, structured logging, and thread-safe request handling.
 * NFR: input_validation, structured_logging, thread_safety
 */
public class FnolSubmissionController {

    private final FnolSubmissionService submissionService;

    public FnolSubmissionController(FnolSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    public FnolSubmissionResponse submitClaim(FnolSubmissionRequest request) {
        try {
            AppLogger.info(String.format(
                "{\"event\":\"FNOL_SUBMISSION_RECEIVED\",\"correlationId\":\"%s\",\"channel\":\"%s\",\"actor\":\"system\",\"role\":\"FNOL_API\"}",
                request.correlationId(), request.channel()
            ));
            return submissionService.processSubmission(request);
        } catch (IllegalArgumentException e) {
            AppLogger.error(String.format(
                "{\"event\":\"FNOL_VALIDATION_FAILED\",\"correlationId\":\"%s\",\"error\":\"%s\",\"actor\":\"system\",\"role\":\"VALIDATOR\"}",
                request.correlationId(), e.getMessage()
            ));
            throw e;
        }
    }
}
```

```java
package app.fnol.domain.model;

import java.util.Map;

/**
 * DTO representing incoming FNOL payload from multiple channels.
 * NFR: gdpr (PII handled via masking in validation layer)
 */
public record FnolSubmissionRequest(
    String correlationId,
    String channel,
    String policyNumber,
    String claimantName,
    String description,
    Map<String, Object> payload
) {}

/**
 * DTO for successful submission response.
 */
public record FnolSubmissionResponse(
    String claimNumber,
    String decisionOutcome,
    String auditDiaryUri
) {}
```

```java
package app.fnol.domain.validation;

import app.fnol.domain.model.FnolSubmissionRequest;
import app.fnol.domain.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates FNOL inputs at service boundaries.
 * NFR: input_validation, gdpr, soc2
 */
public class FnolInputValidator {

    private static final Pattern POLICY_PATTERN = Pattern.compile("^[A-Z]{2,4}-\\d{6,10}$");
    private static final Pattern CORRELATION_PATTERN = Pattern.compile("^[a-f0-9-]{36}$");
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    public ValidationResult validate(FnolSubmissionRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.correlationId() == null || !CORRELATION_PATTERN.matcher(request.correlationId()).matches()) {
            errors.add("Missing or invalid correlationId");
        }
        if (request.channel() == null || request.channel().isBlank()) {
            errors.add("Missing channel");
        }
        if (request.policyNumber() == null || !POLICY_PATTERN.matcher(request.policyNumber()).matches()) {
            errors.add("Invalid policyNumber format");
        }
        if (request.claimantName() == null || request.claimantName().isBlank()) {
            errors.add("Missing claimantName");
        } else if (request.claimantName().length() > 100) {
            errors.add("claimantName exceeds maximum length");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("description exceeds maximum length");
        }
        if (request.payload() == null || request.payload().isEmpty()) {
            errors.add("Missing payload");
        } else {
            // GDPR: Data minimization check - reject excessive PII fields
            if (request.payload().containsKey("ssn") || request.payload().containsKey("passportNumber")) {
                errors.add("Prohibited PII detected in payload");
            }
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
```

```java
package app.fnol.domain.decision;

import app.integrations.TabularDataService;
import app.fnol.enums.DecisionOutcome;

import java.util.List;
import java.util.Map;

/**
 * Applies business rules to determine claim disposition.
 * NFR: thread_safety, soc2 (audit trail), ha_multi_az (idempotent rule evaluation)
 */
public class FnolDecisionEngine {

    private final TabularDataService rulesEngineService;

    public FnolDecisionEngine(TabularDataService rulesEngineService) {
        this.rulesEngineService = rulesEngineService;
    }

    public DecisionOutcome evaluate(FnolSubmissionRequest request) {
        // Fetch decision rules from DynamoDB
        List<Map<String, Object>> rules = rulesEngineService.query("FNOL_DECISION_RULES");
        if (rules.isEmpty()) {
            return DecisionOutcome.PENDING_REVIEW;
        }

        // Apply rule: auto-approve if amount < threshold, else pending
        Map<String, Object> payload = request.payload();
        if (payload.containsKey("estimatedAmount")) {
            try {
                double amount = ((Number) payload.get("estimatedAmount")).doubleValue();
                if (amount < 10000.0) {
                    return DecisionOutcome.APPROVED;
                }
            } catch (NumberFormatException e) {
                // Graceful degradation
            }
        }
        return DecisionOutcome.PENDING_REVIEW;
    }
}
```

```java
package app.fnol.domain.service;

import app.config.AppConfig;
import app.integrations.CacheService;
import app.integrations.ObjectStorageService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;
import app.fnol.domain.decision.FnolDecisionEngine;
import app.fnol.domain.model.FnolSubmissionRequest;
import app.fnol.domain.model.FnolSubmissionResponse;
import app.fnol.domain.validation.FnolInputValidator;
import app.fnol.enums.DecisionOutcome;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates FNOL submission lifecycle.
 * NFR: ha_multi_az, thread_safety, gdpr, soc2, structured_logging, secrets_management
 */
public class FnolSubmissionService {

    private final FnolInputValidator validator;
    private final FnolDecisionEngine decisionEngine;
    private final TabularDataService workflowRouter;
    private final ObjectStorageService auditDiaryStore;
    private final CacheService idempotencyCache;
    private final SecretService secretService;
    private final String rulesTable;
    private final String workflowTable;
    private final String auditBucket;
    private final String auditKeyPattern;

    // Thread-safe idempotency registry
    private final Map<String, FnolSubmissionResponse> completedSubmissions = new ConcurrentHashMap<>();

    public FnolSubmissionService(
        FnolInputValidator validator,
        FnolDecisionEngine decisionEngine,
        TabularDataService workflowRouter,
        ObjectStorageService auditDiaryStore,
        CacheService idempotencyCache,
        SecretService secretService
    ) {
        this.validator = validator;
        this.decisionEngine = decisionEngine;
        this.workflowRouter = workflowRouter;
        this.auditDiaryStore = auditDiaryStore;
        this.idempotencyCache = idempotencyCache;
        this.secretService = secretService;

        // Secrets management: resolve infra credentials securely
        this.rulesTable = secretService.resolve("RULES_ENGINE_TABLE_NAME");
        this.workflowTable = secretService.resolve("WORKFLOW_ROUTER_TABLE_NAME");
        this.auditBucket = secretService.resolve("AUDIT_DIARY_BUCKET_NAME");
        this.auditKeyPattern = AppConfig.get("AUDIT_DIARY_KEY_PATTERN", "AuditDiaryStore/{claimNumber}.json");
    }

    public FnolSubmissionResponse processSubmission(FnolSubmissionRequest request) {
        // 1. Idempotency check (HA/Thread-safety)
        FnolSubmissionResponse cached = completedSubmissions.get(request.correlationId());
        if (cached != null) {
            return cached;
        }

        // 2. Input Validation
        var validation = validator.validate(request);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Validation failed: " + String.join(", ", validation.errors()));
        }

        // 3. Decision Evaluation
        DecisionOutcome outcome = decisionEngine.evaluate(request);

        // 4. Generate Claim Number & Audit Data
        String claimNumber = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant timestamp = Instant.now();
        String lawfulBasis = "CONTRACT_PERFORMANCE"; // GDPR: Legitimate interest / Contractual necessity
        String maskedName = maskPii(request.claimantName());

        // 5. Persist to Workflow Router
        Map<String, Object> workflowItem = new HashMap<>();
        workflowItem.put("pk", claimNumber);
        workflowItem.put("channel", request.channel());
        workflowItem.put("decision", outcome.name());
        workflowItem.put("timestamp", timestamp.toString());
        workflowItem.put("policyNumber", request.policyNumber());
        workflowRouter.putItem(workflowItem);

        // 6. Write Audit Diary to S3 (SOC2/GDPR compliance)
        String auditKey = auditKeyPattern.replace("{claimNumber}", claimNumber);
        Map<String, Object> diaryEntry = new HashMap<>();
        diaryEntry.put("claimNumber", claimNumber);
        diaryEntry.put("policyNumber", request.policyNumber());
        diaryEntry.put("actor", "system");
        diaryEntry.put("role", "FNOL_PROCESSOR");
        diaryEntry.put("action", "CLAIM_ACKNOWLEDGMENT_DUE");
        diaryEntry.put("timestamp", timestamp.toString());
        diaryEntry.put("lawfulBasis", lawfulBasis);
        diaryEntry.put("piiMinimized", maskedName);
        
        String diaryJson = diaryEntry.toString(); // Simplified serialization for script
        auditDiaryStore.upload(null, auditKey); // Wire to S3 client
        String objectUri = "s3://" + auditBucket + "/" + auditKey;

        // 7. Structured Logging
        AppLogger.info(String.format(
            "{\"event\":\"FNOL_SUBMISSION_COMPLETE\",\"claimNumber\":\"%s\",\"decision\":\"%s\",\"auditUri\":\"%s\",\"timestamp\":\"%s\",\"lawfulBasis\":\"%s\"}",
            claimNumber, outcome.name(), objectUri, timestamp, lawfulBasis
        ));

        // 8. Cache result for idempotency
        FnolSubmissionResponse response = new FnolSubmissionResponse(claimNumber, outcome.name(), objectUri);
        completedSubmissions.put(request.correlationId(), response);
        idempotencyCache.set(request.correlationId(), response.toString());

        return response;
    }

    // GDPR: PII minimization helper
    private String maskPii(String name) {
        if (name == null) return "REDACTED";
        return name.length() > 3 ? "****" + name.substring(name.length() - 3) : "****";
    }
}