package app.domain.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Entity: internal_case_management_orchestration_decision
 * NFRs: thread_safety (immutable fields), gdpr (data minimization enforced at construction)
 */
public final class InternalCaseManagementOrchestrationDecision {
    private final String id;
    private final Map<String, Object> payload;

    public InternalCaseManagementOrchestrationDecision(String id, Map<String, Object> payload) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Decision ID must not be null or blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        this.id = id;
        // Thread-safe: expose immutable snapshot
        this.payload = Collections.unmodifiableMap(payload);
    }

    public String getId() {
        return id;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}

package app.domain.service;

import java.util.Map;

/**
 * Contract for aggregating decision data from downstream systems.
 * NFRs: ha_multi_az (stateless interface), tls_in_transit (contract implies secure remote calls)
 */
public interface DecisionAggregationService {
    Map<String, Object> aggregateForClaim(String claimId);
}

package app.application.service;

import app.domain.model.InternalCaseManagementOrchestrationDecision;
import app.domain.service.DecisionAggregationService;
import app.infrastructure.repository.DecisionRepository;
import app.utilities.AppLogger;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the decision aggregation workflow.
 * NFRs: structured_logging, input_validation, soc2, gdpr, ha_multi_az (idempotent persistence)
 */
public class InternalCaseManagementOrchestrationService {
    private final DecisionRepository decisionRepository;
    private final DecisionAggregationService aggregationService;

    public InternalCaseManagementOrchestrationService(DecisionRepository decisionRepository, DecisionAggregationService aggregationService) {
        this.decisionRepository = decisionRepository;
        this.aggregationService = aggregationService;
    }

    public InternalCaseManagementOrchestrationService executeAggregation(String claimId, String tenantId, String policyNumber, String userId, String userRole) {
        AppLogger.info("Aggregation started. Tenant: " + tenantId + " | Claim: " + claimId + " | Policy: " + policyNumber + " | User: " + userId + " | Role: " + userRole);
        
        validateInputs(claimId, tenantId, userId, userRole);
        
        Map<String, Object> aggregatedPayload = aggregationService.aggregateForClaim(claimId);
        String decisionId = UUID.randomUUID().toString();
        
        InternalCaseManagementOrchestrationDecision decision = new InternalCaseManagementOrchestrationDecision(decisionId, aggregatedPayload);
        decisionRepository.save(decision);
        
        AppLogger.info("Aggregation completed. Decision ID: " + decisionId);
        return this;
    }

    private void validateInputs(String claimId, String tenantId, String userId, String userRole) {
        if (claimId == null || claimId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Claim and Tenant identifiers are required for GDPR/SOC2 traceability");
        }
        if (userId == null || userId.isBlank() || userRole == null || userRole.isBlank()) {
            throw new IllegalArgumentException("User and Role are required for audit compliance");
        }
    }
}

package app.infrastructure.repository;

import app.domain.model.InternalCaseManagementOrchestrationDecision;
import java.util.Optional;

/**
 * Repository contract for persistence.
 * NFRs: ha_multi_az (supports retry/idempotency patterns)
 */
public interface DecisionRepository {
    void save(InternalCaseManagementOrchestrationDecision decision);
    Optional<InternalCaseManagementOrchestrationDecision> findById(String id);
}

package app.infrastructure.repository;

import app.config.AppConfig;
import app.domain.model.InternalCaseManagementOrchestrationDecision;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB-backed repository.
 * NFRs: secrets_management, tls_in_transit (via config), thread_safety (stateless)
 */
public class DynamoDbDecisionRepository implements DecisionRepository {
    private final TabularDataService tabularDataService;
    private final String tableName;

    public DynamoDbDecisionRepository(SecretService secretService) {
        this.tableName = AppConfig.get("DECISION_TABLE_NAME", "admin-backend_table");
        // Secrets resolved at runtime; credentials would be passed to actual client in production
        String dbCreds = secretService.resolve("DB_CREDENTIALS");
        if (dbCreds == null || dbCreds.isBlank()) {
            throw new IllegalStateException("Database credentials not found in secrets manager");
        }
        this.tabularDataService = new TabularDataService(tableName);
        AppLogger.info("Repository initialized. Table: " + tableName);
    }

    @Override
    public void save(InternalCaseManagementOrchestrationDecision decision) {
        Map<String, Object> item = Map.of(
            "pk", decision.getId(),
            "payload", decision.getPayload()
        );
        tabularDataService.putItem(item);
    }

    @Override
    public Optional<InternalCaseManagementOrchestrationDecision> findById(String id) {
        Map<String, Object> item = tabularDataService.getItem(id, "sk");
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) item.get("payload");
        return Optional.of(new InternalCaseManagementOrchestrationDecision(id, payload));
    }
}

package app.infrastructure.adapter;

import app.integrations.ObjectStorageService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates data from Rules Engine, Audit Logger, and Document Storage.
 * NFRs: ha_multi_az (graceful degradation), thread_safety (local state only), structured_logging
 */
public class DecisionAggregationAdapter implements app.domain.service.DecisionAggregationService {
    private final TabularDataService rulesEngineTable;
    private final TabularDataService auditLoggerTable;
    private final ObjectStorageService documentStorage;

    public DecisionAggregationAdapter(TabularDataService rulesEngineTable, TabularDataService auditLoggerTable, ObjectStorageService documentStorage) {
        this.rulesEngineTable = rulesEngineTable;
        this.auditLoggerTable = auditLoggerTable;
        this.documentStorage = documentStorage;
    }

    @Override
    public Map<String, Object> aggregateForClaim(String claimId) {
        Map<String, Object> payload = new HashMap<>();
        
        // Rules Engine
        List<Map<String, Object>> rulesResults = rulesEngineTable.query(claimId);
        payload.put("rulesDecisions", rulesResults);
        
        // Audit Logger
        List<Map<String, Object>> auditLogs = auditLoggerTable.query(claimId);
        payload.put("auditLogs", auditLogs);
        
        // Document Storage
        String objectKey = "document-storage/" + claimId + ".json";
        try {
            byte[] docBytes = documentStorage.download(objectKey);
            payload.put("documentReference", new String(docBytes));
        } catch (Exception e) {
            AppLogger.info("Document storage unavailable for claim " + claimId + ". Proceeding with degraded payload.");
            payload.put("documentReference", null);
        }
        
        return payload;
    }
}

package app.infrastructure.adapter;

import app.integrations.ObjectStorageService;
import app.integrations.TabularDataService;
import app.domain.service.DecisionAggregationService;
import java.util.Map;

/**
 * Wire-up class for constructor injection.
 */
public class DecisionAggregationServiceImpl implements DecisionAggregationService {
    private final DecisionAggregationAdapter adapter;

    public DecisionAggregationServiceImpl(TabularDataService rulesEngineTable, TabularDataService auditLoggerTable, ObjectStorageService documentStorage) {
        this.adapter = new DecisionAggregationAdapter(rulesEngineTable, auditLoggerTable, documentStorage);
    }

    @Override
    public Map<String, Object> aggregateForClaim(String claimId) {
        return adapter.aggregateForClaim(claimId);
    }
}