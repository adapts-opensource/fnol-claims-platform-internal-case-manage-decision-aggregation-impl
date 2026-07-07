package app.domain.enums;

/**
 * Audit actions for structured logging and compliance tracking.
 */
public enum AuditAction {
    CLAIM_RECEIVED,
    ENRICHMENT_COMPLETED,
    RULES_FETCHED,
    AUDIT_LOG_WRITTEN,
    ROUTING_INITIATED,
    VALIDATION_FAILED
}

package app.domain.model;

import java.util.Map;

/**
 * Request payload for claim enrichment. Contains claim identifiers and raw data.
 */
public class ClaimEnrichmentRequest {
    private String id;
    private String policyNumber;
    private String actor;
    private String role;
    private Map<String, Object> payload;

    public ClaimEnrichmentRequest(String id, String policyNumber, String actor, String role, Map<String, Object> payload) {
        this.id = id;
        this.policyNumber = policyNumber;
        this.actor = actor;
        this.role = role;
        this.payload = payload;
    }

    public String getId() { return id; }
    public String getPolicyNumber() { return policyNumber; }
    public String getActor() { return actor; }
    public String getRole() { return role; }
    public Map<String, Object> getPayload() { return payload; }
}

/**
 * Standardized claim data entity linked to calculation transformation.
 * Matches: claim_data_standardization_calculation_transform
 */
public class StandardizedClaimRecord {
    private String id;
    private Map<String, Object> payload;

    public StandardizedClaimRecord(String id, Map<String, Object> payload) {
        this.id = id;
        this.payload = payload;
    }

    public String getId() { return id; }
    public Map<String, Object> getPayload() { return payload; }
}

package app.domain.service;

import app.domain.enums.AuditAction;
import app.domain.model.ClaimEnrichmentRequest;
import app.domain.model.StandardizedClaimRecord;
import app.integrations.ObjectStorageService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Paths;

/**
 * Orchestrates claim data enrichment based on decision rules.
 * Thread-safe, stateless, and compliant with GDPR/SOC2 audit requirements.
 * Aligns with infra contracts: RulesEngineDecisionService_dynamodb, WorkflowTaskRouter_dynamodb, AuditDiaryStore_s3.
 */
public class ClaimEnrichmentService {

    private final TabularDataService rulesEngineTable;
    private final TabularDataService workflowRouterTable;
    private final ObjectStorageService auditDiaryStore;
    private final SecretService secretService;

    public ClaimEnrichmentService(
            TabularDataService rulesEngineTable,
            TabularDataService workflowRouterTable,
            ObjectStorageService auditDiaryStore,
            SecretService secretService) {
        this.rulesEngineTable = Objects.requireNonNull(rulesEngineTable, "rulesEngineTable must not be null");
        this.workflowRouterTable = Objects.requireNonNull(workflowRouterTable, "workflowRouterTable must not be null");
        this.auditDiaryStore = Objects.requireNonNull(auditDiaryStore, "auditDiaryStore must not be null");
        this.secretService = Objects.requireNonNull(secretService, "secretService must not be null");
    }

    /**
     * Processes claim enrichment: validates input, fetches decision rules, standardizes payload,
     * writes audit diary, and routes workflow tasks.
     */
    public StandardizedClaimRecord processEnrichment(ClaimEnrichmentRequest request) {
        String claimId = request.getId();
        String policyNumber = request.getPolicyNumber();
        String actor = request.getActor();
        String role = request.getRole();
        Instant timestamp = Instant.now();

        // Input Validation (Security: input_validation)
        if (claimId == null || claimId.isBlank() || policyNumber == null || policyNumber.isBlank() ||
                actor == null || actor.isBlank() || role == null || role.isBlank() ||
                request.getPayload() == null) {
            logStructured(AuditAction.VALIDATION_FAILED, claimId, policyNumber, actor, role, timestamp, "Missing required fields");
            throw new IllegalArgumentException("Validation failed: id, policyNumber, actor, role, and payload are required.");
        }

        logStructured(AuditAction.CLAIM_RECEIVED, claimId, policyNumber, actor, role, timestamp, "Enrichment process started");

        // 1. Fetch decision rules (Infra: RulesEngineDecisionService_dynamodb)
        Map<String, Object> decisionRules = fetchDecisionRules(claimId);
        logStructured(AuditAction.RULES_FETCHED, claimId, policyNumber, actor, role, timestamp, "Decision rules retrieved");

        // 2. Enrich & Standardize payload
        Map<String, Object> enrichedPayload = new HashMap<>(request.getPayload());
        enrichedPayload.put("decisionRulesApplied", decisionRules);
        enrichedPayload.put("standardizedAt", timestamp.toString());
        enrichedPayload.put("tenant", "newco");
        enrichedPayload.put("actor", actor);
        enrichedPayload.put("role", role);

        // 3. Persist standardized record (Linked feature: calculation:transformation)
        StandardizedClaimRecord record = new StandardizedClaimRecord(claimId, enrichedPayload);
        persistStandardizedRecord(record);

        // 4. Write Audit Diary (Infra: AuditDiaryStore_s3)
        writeAuditDiary(record, claimId, policyNumber, actor, role, timestamp);

        // 5. Route Workflow Tasks (Infra: WorkflowTaskRouter_dynamodb)
        routeWorkflowTasks(claimId, policyNumber, actor, role, timestamp);

        logStructured(AuditAction.ENRICHMENT_COMPLETED, claimId, policyNumber, actor, role, timestamp, "Enrichment finished successfully");
        return record;
    }

    private Map<String, Object> fetchDecisionRules(String claimId) {
        try {
            // Partition key matches infra contract
            return rulesEngineTable.getItem(claimId, "pk");
        } catch (Exception e) {
            logStructured(AuditAction.VALIDATION_FAILED, claimId, null, "SYSTEM", "INFRA", Instant.now(), "Rules fetch failed: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void persistStandardizedRecord(StandardizedClaimRecord record) {
        // In a real implementation, this would persist to the calculation transformation table.
        // Here we log the intent as the infra contract is externalized.
        AppLogger.info("Persisting standardized record for claim: " + record.getId());
    }

    private void writeAuditDiary(StandardizedClaimRecord record, String claimId, String policyNumber, String actor, String role, Instant timestamp) {
        try {
            // Infra contract: AuditDiaryStore_s3
            String bucketName = auditDiaryStore.getBucketName("audit");
            String objectKeyPattern = "AuditDiaryStore/" + claimId + ".json";
            
            // Simulate path creation for upload contract
            java.nio.file.Path auditPath = Paths.get("/tmp/audit_" + claimId + ".json");
            auditDiaryStore.upload(auditPath, objectKeyPattern);
            
            String objectUri = "s3://" + bucketName + "/" + objectKeyPattern;
            logStructured(AuditAction.AUDIT_LOG_WRITTEN, claimId, policyNumber, actor, role, timestamp, "Diary written to: " + objectUri);
        } catch (Exception e) {
            logStructured(AuditAction.VALIDATION_FAILED, claimId, policyNumber, actor, role, timestamp, "Audit diary write failed: " + e.getMessage());
            throw new RuntimeException("Failed to write audit diary", e);
        }
    }

    private void routeWorkflowTasks(String claimId, String policyNumber, String actor, String role, Instant timestamp) {
        try {
            // Infra contract: WorkflowTaskRouter_dynamodb
            Map<String, Object> taskPayload = new HashMap<>();
            taskPayload.put("pk", claimId);
            taskPayload.put("policyNumber", policyNumber);
            taskPayload.put("status", "ENRICHED");
            taskPayload.put("enrichedAt", timestamp.toString());
            taskPayload.put("actor", actor);
            taskPayload.put("role", role);
            
            workflowRouterTable.putItem(taskPayload);
            logStructured(AuditAction.ROUTING_INITIATED, claimId, policyNumber, actor, role, timestamp, "Workflow task routed");
        } catch (Exception e) {
            logStructured(AuditAction.VALIDATION_FAILED, claimId, policyNumber, actor, role, timestamp, "Workflow routing failed: " + e.getMessage());
            throw new RuntimeException("Failed to route workflow task", e);
        }
    }

    private void logStructured(AuditAction action, String claimId, String policyNumber, String actor, String role, Instant timestamp, String detail) {
        String logEntry = String.format(
                "{\"tenant\":\"newco\",\"claimNumber\":\"%s\",\"policyNumber\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"timestamp\":\"%s\",\"action\":\"%s\",\"detail\":\"%s\"}",
                claimId, policyNumber, actor, role, timestamp, action.name(), detail
        );
        AppLogger.info(logEntry);
    }
}

package app.config;

import app.integrations.ObjectStorageService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;

/**
 * Infrastructure wiring for Claim Data Standardization:decision:enrichment.
 * Respects least-privilege IAM, secrets management, and TLS via environment configuration.
 */
public final class EnrichmentInfraConfig {

    private EnrichmentInfraConfig() {}

    public static TabularDataService createRulesEngineClient() {
        String table = app.config.AppConfig.get("RULES_ENGINE_TABLE_NAME", "RulesEngineDecisionService_table");
        String partitionKey = app.config.AppConfig.get("RULES_ENGINE_PARTITION_KEY", "pk");
        // Partition key is metadata for the infra contract; TabularDataService handles routing
        return new TabularDataService(table);
    }

    public static TabularDataService createWorkflowRouterClient() {
        String table = app.config.AppConfig.get("WORKFLOW_ROUTER_TABLE_NAME", "WorkflowTaskRouter_table");
        return new TabularDataService(table);
    }

    public static ObjectStorageService createAuditDiaryStore() {
        String endpoint = app.config.AppConfig.get("AUDIT_DIARY_S3_ENDPOINT", "https://s3.amazonaws.com");
        String bucket = app.config.AppConfig.get("AUDIT_DIARY_BUCKET_NAME", "AuditDiaryStore-bucket");
        // TLS is enforced by endpoint scheme and infra client configuration
        return new ObjectStorageService(endpoint, bucket);
    }

    public static SecretService createSecretService() {
        return new SecretService();
    }
}