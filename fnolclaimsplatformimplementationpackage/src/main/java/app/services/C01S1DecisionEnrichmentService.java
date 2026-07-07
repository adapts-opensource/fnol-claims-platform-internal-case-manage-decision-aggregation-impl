package app.fnol.model;

import java.util.Map;
import java.util.Objects;

public class FnolSubmissionRequest {
    private final String claimId;
    private final String policyNumber;
    private final String channel;
    private final Map<String, Object> payload;

    public FnolSubmissionRequest(String claimId, String policyNumber, String channel, Map<String, Object> payload) {
        this.claimId = Objects.requireNonNull(claimId, "claimId must not be null");
        this.policyNumber = Objects.requireNonNull(policyNumber, "policyNumber must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    public String getClaimId() { return claimId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getChannel() { return channel; }
    public Map<String, Object> getPayload() { return payload; }
}
```

```java
package app.fnol.enums;

public enum ChannelType {
    WEB, MOBILE, AGENT, CALL_CENTER
}
```

```java
package app.fnol.enums;

public enum DiaryEventType {
    CLAIM_ACKNOWLEDGMENT,
    INVESTIGATION_START
}
```

```java
package app.fnol.repository;

import app.integrations.TabularDataService;
import app.integrations.ObjectStorageService;
import app.config.AppConfig;
import app.utilities.AppLogger;
import java.util.Map;
import java.util.HashMap;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RulesEngineRepository {
    private final TabularDataService tabularDataService;
    private final String tableName;
    private final String partitionKey;

    public RulesEngineRepository(TabularDataService tabularDataService) {
        this.tabularDataService = tabularDataService;
        this.tableName = AppConfig.get("RULES_ENGINE_TABLE_NAME", "RulesEngineDecisionService_table");
        this.partitionKey = AppConfig.get("RULES_ENGINE_PARTITION_KEY", "pk");
    }

    public Map<String, Object> queryDecisionRules(String policyNumber) {
        Map<String, Object> item = tabularDataService.getItem(partitionKey, policyNumber);
        AppLogger.info(String.format("{\"action\":\"RULES_QUERY\",\"policy\":\"%s\",\"result\":\"success\"}", policyNumber));
        return item != null ? item : new HashMap<>();
    }
}
```

```java
package app.fnol.repository;

import app.integrations.TabularDataService;
import app.config.AppConfig;
import app.utilities.AppLogger;
import java.util.Map;

public class WorkflowTaskRouterRepository {
    private final TabularDataService tabularDataService;
    private final String tableName;
    private final String partitionKey;

    public WorkflowTaskRouterRepository(TabularDataService tabularDataService) {
        this.tabularDataService = tabularDataService;
        this.tableName = AppConfig.get("WORKFLOW_ROUTER_TABLE_NAME", "WorkflowTaskRouter_table");
        this.partitionKey = AppConfig.get("WORKFLOW_ROUTER_PARTITION_KEY", "pk");
    }

    public void routeTask(Map<String, Object> taskPayload) {
        tabularDataService.putItem(taskPayload);
        AppLogger.info(String.format("{\"action\":\"TASK_ROUTED\",\"claim\":\"%s\",\"status\":\"enqueued\"}", taskPayload.get("claimId")));
    }
}
```

```java
package app.fnol.repository;

import app.integrations.ObjectStorageService;
import app.config.AppConfig;
import app.utilities.AppLogger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

public class AuditDiaryRepository {
    private final ObjectStorageService objectStorageService;
    private final String bucketName;
    private final String keyPattern;

    public AuditDiaryRepository(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
        this.bucketName = AppConfig.get("AUDIT_DIARY_BUCKET", "AuditDiaryStore-bucket");
        this.keyPattern = AppConfig.get("AUDIT_DIARY_KEY_PATTERN", "AuditDiaryStore/{entity_id}.json");
    }

    public String createDiaryEntry(String claimId, String eventType, Map<String, Object> diaryPayload) {
        String objectKey = keyPattern.replace("{entity_id}", claimId);
        String fileName = claimId + "_" + Instant.now().toEpochMilli() + ".json";
        Path tempFile = Paths.get(System.getProperty("java.io.tmpdir"), fileName);
        
        // Simulate JSON serialization to file
        try {
            java.nio.file.Files.write(tempFile, java.util.Base64.getEncoder().encode(
                String.valueOf(diaryPayload).getBytes()
            ));
            objectStorageService.upload(tempFile, objectKey);
            AppLogger.info(String.format("{\"action\":\"DIARY_CREATED\",\"claim\":\"%s\",\"event\":\"%s\",\"uri\":\"s3://%s/%s\"}", 
                claimId, eventType, bucketName, objectKey));
            return "s3://" + bucketName + "/" + objectKey;
        } catch (Exception e) {
            throw new RuntimeException("Diary creation failed for claim: " + claimId, e);
        }
    }
}
```

```java
package app.fnol.service;

import app.integrations.CacheService;
import app.integrations.SecretService;
import app.integrations.ObjectStorageService;
import app.integrations.TabularDataService;
import app.config.AppConfig;
import app.utilities.AppLogger;
import app.fnol.model.FnolSubmissionRequest;
import app.fnol.model.FnolSubmissionResponse;
import app.fnol.enums.DiaryEventType;
import app.fnol.repository.RulesEngineRepository;
import app.fnol.repository.WorkflowTaskRouterRepository;
import app.fnol.repository.AuditDiaryRepository;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;

public class FnolSubmissionService {
    private final RulesEngineRepository rulesEngineRepository;
    private final WorkflowTaskRouterRepository workflowTaskRouterRepository;
    private final AuditDiaryRepository auditDiaryRepository;
    private final CacheService cacheService;
    private final SecretService secretService;
    private final String actor;
    private final String role;

    public FnolSubmissionService(
            RulesEngineRepository rulesEngineRepository,
            WorkflowTaskRouterRepository workflowTaskRouterRepository,
            AuditDiaryRepository auditDiaryRepository,
            CacheService cacheService,
            SecretService secretService) {
        this.rulesEngineRepository = rulesEngineRepository;
        this.workflowTaskRouterRepository = workflowTaskRouterRepository;
        this.auditDiaryRepository = auditDiaryRepository;
        this.cacheService = cacheService;
        this.secretService = secretService;
        this.actor = System.getenv("FNOL_SYSTEM_ACTOR");
        this.role = System.getenv("FNOL_SYSTEM_ROLE");
    }

    public FnolSubmissionResponse processSubmission(FnolSubmissionRequest request) {
        String claimId = request.getClaimId();
        String policyNumber = request.getPolicyNumber();
        String channel = request.getChannel();
        Instant timestamp = Instant.now();

        // 1. Input Validation & Sanitization (GDPR/SOC2)
        validateInput(claimId, policyNumber, channel);

        // 2. Structured Logging: Claim notice received
        logStructuredEvent("CLAIM_RECEIVED", claimId, policyNumber, channel, timestamp);

        // 3. Immediate Diary Creation (NFR: nfr_section)
        Map<String, Object> ackPayload = buildDiaryPayload(claimId, policyNumber, DiaryEventType.CLAIM_ACKNOWLEDGMENT, timestamp);
        auditDiaryRepository.createDiaryEntry(claimId, DiaryEventType.CLAIM_ACKNOWLEDGMENT.name(), ackPayload);

        // 4. Enrichment (Cache + External)
        Map<String, Object> enrichedData = enrichClaimData(claimId, policyNumber);

        // 5. Decision Engine Query
        Map<String, Object> decisionRules = rulesEngineRepository.queryDecisionRules(policyNumber);
        Map<String, Object> decisionPayload = applyDecisionLogic(enrichedData, decisionRules);

        // 6. Standardization Transform Entity Mapping
        Map<String, Object> transformPayload = new HashMap<>();
        transformPayload.put("id", claimId);
        transformPayload.put("payload", decisionPayload);

        // 7. Workflow Routing
        Map<String, Object> taskPayload = new HashMap<>();
        taskPayload.put("claimId", claimId);
        taskPayload.put("policyNumber", policyNumber);
        taskPayload.put("status", "INVESTIGATION_REQUIRED");
        taskPayload.put("enrichedPayload", transformPayload);
        workflowTaskRouterRepository.routeTask(taskPayload);

        // 8. Secondary Diary: Investigation Start Due
        Map<String, Object> invPayload = buildDiaryPayload(claimId, policyNumber, DiaryEventType.INVESTIGATION_START, timestamp);
        auditDiaryRepository.createDiaryEntry(claimId, DiaryEventType.INVESTIGATION_START.name(), invPayload);

        logStructuredEvent("SUBMISSION_COMPLETE", claimId, policyNumber, channel, Instant.now());

        return new FnolSubmissionResponse(claimId, policyNumber, "ACCEPTED", transformPayload);
    }

    private void validateInput(String claimId, String policyNumber, String channel) {
        if (claimId == null || claimId.trim().isEmpty()) throw new IllegalArgumentException("Invalid claimId");
        if (policyNumber == null || policyNumber.trim().isEmpty()) throw new IllegalArgumentException("Invalid policyNumber");
        if (!channel.matches("[A-Z_]+")) throw new IllegalArgumentException("Invalid channel format");
    }

    private Map<String, Object> enrichClaimData(String claimId, String policyNumber) {
        String cacheKey = "fnol:enrich:" + policyNumber;
        String cached = cacheService.get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return Map.of("source", "CACHE", "policyNumber", policyNumber);
        }
        // Simulate external enrichment (TLS enforced via infra config)
        Map<String, Object> enriched = new HashMap<>();
        enriched.put("source", "EXTERNAL");
        enriched.put("policyNumber", policyNumber);
        enriched.put("fraudScore", 0.12);
        enriched.put("coverageStatus", "ACTIVE");
        cacheService.set(cacheKey, java.util.Base64.getEncoder().encodeToString("ACTIVE".getBytes()));
        return enriched;
    }

    private Map<String, Object> applyDecisionLogic(Map<String, Object> enrichment, Map<String, Object> rules) {
        Map<String, Object> decision = new HashMap<>();
        decision.put("enrichment", enrichment);
        decision.put("rulesEvaluated", rules);
        decision.put("autoAdmit", Boolean.FALSE);
        decision.put("requiresInvestigation", Boolean.TRUE);
        return decision;
    }

    private Map<String, Object> buildDiaryPayload(String claimId, String policyNumber, DiaryEventType type, Instant ts) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("claimId", claimId);
        payload.put("policyNumber", policyNumber);
        payload.put("eventType", type.name());
        payload.put("triggeredAt", ts.toString());
        payload.put("retentionDays", 2555); // GDPR/SOC2 compliant retention
        payload.put("erasureEligible", false);
        return payload;
    }

    private void logStructuredEvent(String action, String claimId, String policyNumber, String channel, Instant ts) {
        String log = String.format(
            "{\"tenant\":\"%s\",\"claim\":\"%s\",\"policy\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"timestamp\":\"%s\",\"action\":\"%s\",\"channel\":\"%s\"}",
            AppConfig.get("TENANT_ID", "newco"),
            claimId,
            policyNumber,
            actor != null ? actor : "SYSTEM",
            role != null ? role : "FNOL_PROCESSOR",
            ts.toString(),
            action,
            channel
        );
        AppLogger.info(log);
    }
}
```

```java
package app.fnol.controller;

import app.fnol.model.FnolSubmissionRequest;
import app.fnol.model.FnolSubmissionResponse;
import app.fnol.service.FnolSubmissionService;
import java.util.Map;
import java.util.HashMap;

public class FnolSubmissionController {
    private final FnolSubmissionService fnolSubmissionService;

    public FnolSubmissionController(FnolSubmissionService fnolSubmissionService) {
        this.fnolSubmissionService = fnolSubmissionService;
    }

    public FnolSubmissionResponse submitFnol(Map<String, Object> body) {
        String claimId = (String) body.get("claimId");
        String policyNumber = (String) body.get("policyNumber");
        String channel = (String) body.get("channel");
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        
        FnolSubmissionRequest request = new FnolSubmissionRequest(claimId, policyNumber, channel, payload);
        return fnolSubmissionService.processSubmission(request);
    }
}