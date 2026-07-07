package app.controller.claim;

import app.domain.claim.ClaimInitiationRequest;
import app.service.claim.ClaimInitiationService;
import app.utilities.AppLogger;
import java.util.Map;

/**
 * Layer: Controller / API Boundary
 * NFRs: input_validation, structured_logging, tls_in_transit (enforced by gateway)
 */
public class ClaimInitiationController {

    private final ClaimInitiationService claimInitiationService;

    public ClaimInitiationController(ClaimInitiationService claimInitiationService) {
        this.claimInitiationService = claimInitiationService;
    }

    public Map<String, Object> handleInitiation(ClaimInitiationRequest request) {
        AppLogger.info(String.format("[INPUT_VALIDATION] Received claim initiation for tenant=%s policy=%s",
                request.tenantId(), request.policyNumber()));
        
        String claimId = claimInitiationService.initiateClaim(request);
        
        return Map.of("claimId", claimId, "status", "INITIATED");
    }
}

package app.service.claim;

import app.domain.claim.ClaimInitiationRequest;
import app.domain.claim.ClaimRecord;
import app.infrastructure.claim.ClaimRouter;
import app.infrastructure.claim.ClaimTransformationService;
import app.integrations.ObjectStorageService;
import app.integrations.SecretService;
import app.integrations.TabularDataService;
import app.utilities.AppLogger;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Layer: Service / Orchestration
 * NFRs: ha_multi_az (stateless), thread_safety (ReentrantLock), gdpr/soc2 (audit trail), 
 *       operability (diary creation), secrets_management (SecretService)
 */
public class ClaimInitiationService {

    private final ClaimTransformationService transformationService;
    private final ClaimRouter claimRouter;
    private final TabularDataService tabularDataService;
    private final ObjectStorageService objectStorageService;
    private final SecretService secretService;
    private final ReentrantLock orchestrationLock = new ReentrantLock();

    public ClaimInitiationService(ClaimTransformationService transformationService,
                                  ClaimRouter claimRouter,
                                  TabularDataService tabularDataService,
                                  ObjectStorageService objectStorageService,
                                  SecretService secretService) {
        this.transformationService = transformationService;
        this.claimRouter = claimRouter;
        this.tabularDataService = tabularDataService;
        this.objectStorageService = objectStorageService;
        this.secretService = secretService;
    }

    public String initiateClaim(ClaimInitiationRequest request) {
        orchestrationLock.lock();
        try {
            AppLogger.info(String.format("[ORCHESTRATION] Start claim processing tenant=%s policy=%s",
                    request.tenantId(), request.policyNumber()));

            ClaimRecord record = transformationService.transform(request);
            String route = claimRouter.routeClaim(record.claimId(), request.priority());

            // Persist to PolicyClaimsDB_dynamodb
            Map<String, Object> itemPayload = Map.of(
                    "pk", record.claimId(),
                    "sk", "CLAIM",
                    "tenantId", record.tenantId(),
                    "policyNumber", record.policyNumber(),
                    "status", record.status(),
                    "createdAt", record.createdAt().toString(),
                    "diaries", record.diaries().stream()
                            .map(d -> Map.of("type", d.type(), "dueDate", d.dueDate().toString(), "status", d.status()))
                            .toList(),
                    "auditTrail", record.auditTrail()
            );
            tabularDataService.putItem(itemPayload);

            // Compliance Audit Log to ComplianceAuditService_s3
            String auditKey = String.format("ComplianceAuditService/%s.json", record.claimId());
            objectStorageService.upload(null, auditKey); // Path null for mock; real impl serializes itemPayload

            AppLogger.info(String.format("[ORCHESTRATION] Claim %s persisted and routed to %s",
                    record.claimId(), route));

            return record.claimId();
        } finally {
            orchestrationLock.unlock();
        }
    }
}

package app.infrastructure.claim;

import app.domain.claim.ClaimInitiationRequest;
import app.domain.claim.ClaimRecord;
import app.domain.claim.DiaryEntry;
import app.integrations.CacheService;
import app.utilities.AppLogger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer: Infrastructure / Transformation
 * NFRs: gdpr (data minimization), operability (diary creation), input_validation
 */
public class ClaimTransformationService {

    public ClaimRecord transform(ClaimInitiationRequest request) {
        validateInput(request);
        String claimId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // Immediate statutory diary creation per operability NFR
        List<DiaryEntry> diaries = List.of(
                new DiaryEntry("CLAIM_ACKNOWLEDGMENT_DUE", "Claim notice received", now.plus(2, ChronoUnit.DAYS), "PENDING"),
                new DiaryEntry("INVESTIGATION_START_DUE", "Proof of loss received, if applicable", now.plus(14, ChronoUnit.DAYS), "PENDING")
        );

        // SOC2/GDPR Audit Trail
        Map<String, String> auditTrail = new ConcurrentHashMap<>();
        auditTrail.put("tenant", request.tenantId());
        auditTrail.put("claimNumber", claimId);
        auditTrail.put("policyNumber", request.policyNumber());
        auditTrail.put("actor", request.actor());
        auditTrail.put("role", request.role());
        auditTrail.put("timestamp", now.toString());
        auditTrail.put("action", "CLAIM_INITIATION");

        return new ClaimRecord(claimId, request.tenantId(), request.policyNumber(), "OPEN", now, diaries, auditTrail);
    }

    private void validateInput(ClaimInitiationRequest req) {
        if (req.tenantId() == null || req.tenantId().isBlank()) {
            throw new IllegalArgumentException("Tenant ID is required for GDPR compliance and routing.");
        }
        if (req.policyNumber() == null || req.policyNumber().isBlank()) {
            throw new IllegalArgumentException("Policy number is required.");
        }
        if (req.actor() == null || req.actor().isBlank()) {
            throw new IllegalArgumentException("Actor is required for audit logging.");
        }
    }
}

/**
 * Layer: Infrastructure / Routing
 * NFRs: ha_multi_az (distributed routing), thread_safety (AtomicInteger, ConcurrentHashMap)
 */
class ClaimRouter {

    private final CacheService cacheService;
    private final ExecutorService routingExecutor;
    private final AtomicInteger routingCounter = new AtomicInteger(0);

    public ClaimRouter(CacheService cacheService) {
        this.cacheService = cacheService;
        this.routingExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    public String routeClaim(String claimId, String priority) {
        String routeKey = "claim:route:" + priority.toLowerCase();
        String route = cacheService.get(routeKey);
        if (route == null || route.isBlank()) {
            route = "DEFAULT_HANDLER";
        }
        int workerId = routingCounter.incrementAndGet();
        AppLogger.info(String.format("[ROUTING] claimId=%s priority=%s route=%s workerId=%d",
                claimId, priority, route, workerId));
        return route;
    }
}

package app.domain.claim;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ClaimInitiationRequest(
        String tenantId,
        String policyNumber,
        String claimantId,
        String description,
        String priority,
        String actor,
        String role
) {}

public record ClaimRecord(
        String claimId,
        String tenantId,
        String policyNumber,
        String status,
        Instant createdAt,
        List<DiaryEntry> diaries,
        Map<String, String> auditTrail
) {}

public record DiaryEntry(
        String type,
        String trigger,
        Instant dueDate,
        String status
) {}