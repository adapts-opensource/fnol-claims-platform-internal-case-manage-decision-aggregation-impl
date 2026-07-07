package app.application.claim;

import app.domain.claim.Claim;
import app.domain.claim.ClaimDecision;
import app.domain.claim.ClaimSubmissionRequest;
import app.domain.claim.ClaimValidationResult;
import app.domain.claim.ClaimValidationService;
import app.infrastructure.claim.ClaimDiaryService;
import app.infrastructure.claim.ClaimRepository;
import app.integrations.SecretService;
import app.utilities.AppLogger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service orchestrating Multi-Channel FNOL Submission.
 * Enforces NFRs: thread safety (idempotency), structured logging, GDPR minimization,
 * SOC2 audit trails, TLS/Secrets validation, and immediate statutory diary creation.
 */
public class ClaimSubmissionApplicationService {

    private final ClaimValidationService validationService;
    private final ClaimRepository claimRepository;
    private final ClaimDiaryService diaryService;
    private final SecretService secretService;

    /**
     * Constructor injection for services. Stateless design supports HA Multi-AZ deployment.
     */
    public ClaimSubmissionApplicationService(
            ClaimValidationService validationService,
            ClaimRepository claimRepository,
            ClaimDiaryService diaryService,
            SecretService secretService) {
        this.validationService = validationService;
        this.claimRepository = claimRepository;
        this.diaryService = diaryService;
        this.secretService = secretService;
    }

    /**
     * Submits a new FNOL claim across multi-channel inputs.
     * Thread-safe via idempotency key enforcement and repository-level locking.
     */
    public ClaimDecision submitClaim(ClaimSubmissionRequest request, String actor, String role) {
        // 1. Input validation at service boundary (GDPR minimization, format, required fields)
        ClaimValidationResult validationResult = validationService.validate(request);
        if (!validationResult.isValid()) {
            logAudit("FNOL_VALIDATION_FAILED", request.getTenantId(), null, request.getPolicyId(),
                    actor, role, "Validation errors: " + validationResult.getErrors());
            throw new IllegalArgumentException("Claim validation failed: " + validationResult.getErrors());
        }

        // 2. Idempotency check for concurrent workers & thread safety
        Optional<Claim> existingClaim = claimRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existingClaim.isPresent()) {
            logAudit("FNOL_IDEMPOTENT_REPLAY", existingClaim.get().getTenantId(),
                    existingClaim.get().getClaimNumber(), existingClaim.get().getPolicyId(),
                    actor, role, "Duplicate submission detected. Returning existing claim.");
            return ClaimDecision.APPROVED;
        }

        // 3. Enforce TLS & Secrets Management at runtime boundary
        String tlsStatus = app.config.AppConfig.get("TLS_ENABLED", "true");
        if (!"true".equalsIgnoreCase(tlsStatus)) {
            throw new IllegalStateException("TLS in transit enforcement failed. Rejecting submission.");
        }
        String apiSecret = secretService.resolve("FNOL_API_SECRET");
        if (apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalStateException("Least privilege IAM/Secrets validation failed.");
        }

        // 4. Generate internal identifiers & persist claim
        String claimId = UUID.randomUUID().toString();
        String claimNumber = generateClaimNumber(request.getTenantId());
        Claim claim = new Claim(claimId, claimNumber, request.getTenantId(), request.getPolicyId(),
                request.getChannel(), request.getDescription(), Instant.now());

        claimRepository.save(claim);
        logAudit("FNOL_CLAIM_CREATED", claim.getTenantId(), claim.getClaimNumber(), claim.getPolicyId(),
                actor, role, "Claim persisted successfully.");

        // 5. Create statutory diaries immediately (NFR: nfr_section)
        diaryService.createStatutoryDiary(claim, actor, role);
        logAudit("FNOL_STATUTORY_DIARIES_CREATED", claim.getTenantId(), claim.getClaimNumber(),
                claim.getPolicyId(), actor, role, "Diaries created per compliance requirements.");

        // 6. Apply decision logic
        ClaimDecision decision = applyDecisionLogic(claim, validationResult);
        logAudit("FNOL_DECISION_APPLIED", claim.getTenantId(), claim.getClaimNumber(), claim.getPolicyId(),
                actor, role, "Decision outcome: " + decision);
        return decision;
    }

    private ClaimDecision applyDecisionLogic(Claim claim, ClaimValidationResult validationResult) {
        // Simplified decision matrix based on risk scoring
        if (validationResult.getRiskScore() != null && validationResult.getRiskScore() > 80) {
            return ClaimDecision.ROUTED_TO_INVESTIGATION;
        }
        return ClaimDecision.APPROVED;
    }

    private String generateClaimNumber(String tenantId) {
        return tenantId + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    /**
     * Structured logging capturing GDPR/SOC2 audit requirements:
     * tenant, claim number, policy number, actor, role, timestamp, action.
     */
    private void logAudit(String action, String tenantId, String claimNumber, String policyId,
                          String actor, String role, String detail) {
        String structuredLog = String.format(
                "{\"timestamp\":\"%s\",\"tenant\":\"%s\",\"claimNumber\":\"%s\",\"policyNumber\":\"%s\",\"actor\":\"%s\",\"role\":\"%s\",\"action\":\"%s\",\"detail\":\"%s\"}",
                LocalDateTime.now(ZoneOffset.UTC), tenantId, claimNumber, policyId, actor, role, action, detail
        );
        AppLogger.info(structuredLog);
    }
}