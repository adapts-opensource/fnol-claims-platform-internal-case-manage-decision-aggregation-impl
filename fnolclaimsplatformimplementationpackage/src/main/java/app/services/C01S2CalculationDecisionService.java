package app.domain.claim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Validates and holds incoming claim data for standardization.
 * Enforces input_validation NFR at service boundaries.
 */
public record ClaimStandardizationInput(
    String claimId,
    String claimNumber,
    String tenantId,
    String policyId,
    String lossDate,
    String lossType,
    Double reportedAmount,
    String idempotencyKey
) {
    public ClaimStandardizationInput {
        Objects.requireNonNull(claimId, "claimId is required");
        Objects.requireNonNull(claimNumber, "claimNumber is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(policyId, "policyId is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required for thread safety and idempotency");
    }
}

/**
 * Holds standardized claim metrics post-calculation.
 */
public record StandardizationResult(
    String claimId,
    String claimNumber,
    String tenantId,
    String policyId,
    Instant standardizedLossDate,
    String standardizedLossType,
    Double calculatedDeductible,
    Double estimatedLossAmount,
    String status
) {}