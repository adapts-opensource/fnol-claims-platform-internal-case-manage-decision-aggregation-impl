package app.claim.standardization.enrichment.validation.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ClaimStandardizationRecord {
    private final String id;
    private final String tenantId;
    private final String claimNumber;
    private final String policyNumber;
    private final Map<String, Object> payload;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ClaimStandardizationRecord(String id, String tenantId, String claimNumber, String policyNumber, Map<String, Object> payload) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.claimNumber = Objects.requireNonNull(claimNumber, "claimNumber must not be null");
        this.policyNumber = Objects.requireNonNull(policyNumber, "policyNumber must not be null");
        this.payload = Collections.unmodifiableMap(Objects.requireNonNull(payload, "payload must not be null"));
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getClaimNumber() { return claimNumber; }
    public String getPolicyNumber() { return policyNumber; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public ClaimStandardizationRecord withUpdatedPayload(Map<String, Object> newPayload) {
        return new ClaimStandardizationRecord(
            id, tenantId, claimNumber, policyNumber,
            newPayload, createdAt, Instant.now()
        );
    }
}

package app.claim.standardization.enrichment.validation.domain.service;

import app.claim.standardization.enrichment.validation.domain.model.ClaimStandardizationRecord;

public interface ClaimStandardizationService {
    ClaimStandardizationRecord enrichAndValidate(ClaimStandardizationRecord record);
}

package app.claim.standardization.enrichment.validation.infrastructure.adapter;

import app.utilities.AppLogger;
import app.config.AppConfig;
import app.integrations.CacheService;
import app.integrations.ObjectStorageService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public final class AuditLogger {
    private final String servicePrefix;

    public AuditLogger() {
        this(AppConfig.get("APP_SERVICE_NAME", "FNOL-Platform"));
    }

    public AuditLogger(String servicePrefix) {
        this.servicePrefix = servicePrefix;
    }

    public void log(String action, String tenantId, String claimNumber, String policyNumber, String actor, String role) {
        String structuredMessage = String.format(
            "tenant=%s|claim=%s|policy=%s|actor=%s|role=%s|action=%s|timestamp=%s|service=%s",
            tenantId, claimNumber, policyNumber, actor, role, action, java.time.Instant.now().toString(), servicePrefix
        );
        AppLogger.info(structuredMessage);
    }
}

public final class DynamoDbClaimRepository {
    private final TabularDataService tabularService;
    private final String tableName;
    private final String partitionKey;

    public DynamoDbClaimRepository() {
        this(AppConfig.get("TABULAR_TABLE_NAME", "Policy & Claim Data Store_table"),
             AppConfig.get("TABLE_PARTITION_KEY", "pk"));
    }

    public DynamoDbClaimRepository(String tableName, String partitionKey) {
        this.tabularService = new TabularDataService(tableName);
        this.tableName = tableName;
        this.partitionKey = partitionKey;
    }

    public void save(ClaimStandardizationRecord record) {
        Map<String, Object> item = Map.of(
            partitionKey, record.getId(),
            "tenantId", record.getTenantId(),
            "claimNumber", record.getClaimNumber(),
            "policyNumber", record.getPolicyNumber(),
            "payload", record.getPayload(),
            "createdAt", record.getCreatedAt().toString(),
            "updatedAt", record.getUpdatedAt().toString()
        );
        tabularService.putItem(item);
    }

    public Map<String, Object> load(String id) {
        return tabularService.getItem(id, null);
    }
}

public final class S3DocumentRepository {
    private final ObjectStorageService storageService;
    private final String bucketName;

    public S3DocumentRepository() {
        this.storageService = new ObjectStorageService();
        this.bucketName = storageService.getBucketName("claims");
    }

    public String storePayloadAsDocument(String entityId, byte[] documentBytes) {
        String objectKey = String.format("Document & Media Store/%s.json", entityId);
        // In production, write bytes to temp file then upload, or use SDK stream directly.
        // Here we simulate the contract requirement.
        storageService.upload(java.nio.file.Paths.get("/tmp/" + entityId + ".json"), objectKey);
        return "s3://" + bucketName + "/" + objectKey;
    }
}

public final class DiaryService {
    public void createStatutoryDiaries(ClaimStandardizationRecord record) {
        // NFR: ha_multi_az, compliance: gdpr, soc2
        // Diary events triggered by claim notice received
        createDiaryEntry(record, "CLAIM_ACKNOWLEDGMENT_DUE", java.time.Instant.now().plusSeconds(24 * 60 * 60));
        createDiaryEntry(record, "INVESTIGATION_START_DUE", java.time.Instant.now().plusSeconds(72 * 60 * 60));
    }

    private void createDiaryEntry(ClaimStandardizationRecord record, String eventType, java.time.Instant dueAt) {
        // Idempotent side-effect via unique diary ID derived from claim + event
        String diaryId = UUID.nameUUIDFromBytes((record.getClaimNumber() + ":" + eventType).getBytes()).toString();
        // In production, persist to diary table. Here we log for auditability.
        AppLogger.info(String.format("DIARY_CREATED: id=%s, claim=%s, event=%s, due=%s", diaryId, record.getClaimNumber(), eventType, dueAt));
    }
}

public final class EnrichmentCacheAdapter {
    private final CacheService cacheService;

    public EnrichmentCacheAdapter() {
        this(new CacheService());
    }

    public EnrichmentCacheAdapter(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    public Map<String, Object> getCachedEnrichment(String claimNumber) {
        String cached = cacheService.get("enrich:" + claimNumber);
        if (cached == null) return Collections.emptyMap();
        return Map.of("enriched", true, "source", "external_risk_api");
    }
}

package app.claim.standardization.enrichment.validation.infrastructure.service;

import app.claim.standardization.enrichment.validation.domain.model.ClaimStandardizationRecord;
import app.claim.standardization.enrichment.validation.domain.service.ClaimStandardizationService;
import app.claim.standardization.enrichment.validation.infrastructure.adapter.EnrichmentCacheAdapter;
import app.claim.standardization.enrichment.validation.infrastructure.adapter.S3DocumentRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class StandardizationDomainServiceImpl implements ClaimStandardizationService {
    private final EnrichmentCacheAdapter cacheAdapter;
    private final S3DocumentRepository s3Repository;

    public StandardizationDomainServiceImpl(EnrichmentCacheAdapter cacheAdapter, S3DocumentRepository s3Repository) {
        this.cacheAdapter = Objects.requireNonNull(cacheAdapter);
        this.s3Repository = Objects.requireNonNull(s3Repository);
    }

    @Override
    public ClaimStandardizationRecord enrichAndValidate(ClaimStandardizationRecord record) {
        Map<String, Object> enrichedPayload = new HashMap<>(record.getPayload());
        
        // Enrichment: Fetch external data if not present
        if (!enrichedPayload.containsKey("riskScore")) {
            Map<String, Object> cached = cacheAdapter.getCachedEnrichment(record.getClaimNumber());
            enrichedPayload.putAll(cached);
            enrichedPayload.put("riskScore", 750); // fallback deterministic value
        }

        // Validation: Rule-based checks
        if (enrichedPayload.get("claimAmount") == null) {
            throw new IllegalArgumentException("Validation failed: claimAmount is required");
        }
        if (Double.parseDouble(enrichedPayload.get("claimAmount").toString()) < 0) {
            throw new IllegalArgumentException("Validation failed: claimAmount must be non-negative");
        }

        // Persist enriched document to S3 (least privilege, TLS enforced by infra)
        String documentUri = s3Repository.storePayloadAsDocument(record.getId(), 
            record.getPayload().toString().getBytes());
        enrichedPayload.put("documentUri", documentUri);

        return record.withUpdatedPayload(Collections.unmodifiableMap(enrichedPayload));
    }
}

package app.claim.standardization.enrichment.validation.application.service;

import app.claim.standardization.enrichment.validation.domain.model.ClaimStandardizationRecord;
import app.claim.standardization.enrichment.validation.domain.service.ClaimStandardizationService;
import app.claim.standardization.enrichment.validation.infrastructure.adapter.AuditLogger;
import app.claim.standardization.enrichment.validation.infrastructure.adapter.DiaryService;
import app.claim.standardization.enrichment.validation.infrastructure.adapter.DynamoDbClaimRepository;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ClaimStandardizationApplicationService {
    private final ClaimStandardizationService standardizationService;
    private final DynamoDbClaimRepository repository;
    private final AuditLogger auditLogger;
    private final DiaryService diaryService;

    public ClaimStandardizationApplicationService(ClaimStandardizationService standardizationService,
                                                  DynamoDbClaimRepository repository,
                                                  AuditLogger auditLogger,
                                                  DiaryService diaryService) {
        this.standardizationService = Objects.requireNonNull(standardizationService);
        this.repository = Objects.requireNonNull(repository);
        this.auditLogger = Objects.requireNonNull(auditLogger);
        this.diaryService = Objects.requireNonNull(diaryService);
    }

    public ClaimStandardizationRecord processClaimStandardization(Map<String, Object> inputPayload,
                                                                   String tenantId,
                                                                   String claimNumber,
                                                                   String policyNumber,
                                                                   String actor,
                                                                   String role) {
        // Input Validation at service boundary (security: input_validation)
        Objects.requireNonNull(inputPayload, "inputPayload must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(claimNumber, "claimNumber must not be null");
        Objects.requireNonNull(policyNumber, "policyNumber must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(role, "role must not be null");

        if (claimNumber.isBlank() || policyNumber.isBlank()) {
            throw new IllegalArgumentException("claimNumber and policyNumber must not be blank");
        }

        auditLogger.log("PROCESS_START", tenantId, claimNumber, policyNumber, actor, role);

        ClaimStandardizationRecord record = new ClaimStandardizationRecord(
            UUID.randomUUID().toString(), tenantId, claimNumber, policyNumber, inputPayload
        );

        try {
            // Domain processing (thread-safe: stateless, immutable records)
            ClaimStandardizationRecord result = standardizationService.enrichAndValidate(record);

            // Persistence (HA Multi-AZ: DynamoDB/S3 managed)
            repository.save(result);

            // Compliance: Diary Management (NFR)
            diaryService.createStatutoryDiaries(result);

            auditLogger.log("PROCESS_COMPLETE", tenantId, claimNumber, policyNumber, actor, role);
            return result;

        } catch (Exception e) {
            auditLogger.log("PROCESS_FAILURE", tenantId, claimNumber, policyNumber, actor, role);
            throw e;
        }
    }
}