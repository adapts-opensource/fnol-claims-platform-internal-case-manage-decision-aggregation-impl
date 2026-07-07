// Feature: Internal Case Management:decision:aggregation
package app.enums;

/**
 * NFR: soc2, gdpr
 * Enumerated roles and actions for strict audit logging and least-privilege IAM mapping.
 */
public enum AuditAction {
    AGGREGATION_STARTED("AGGREGATION_STARTED"),
    AGGREGATION_COMPLETED("AGGREGATION_COMPLETED"),
    DECISION_SAVED("DECISION_SAVED"),
    VALIDATION_FAILED("VALIDATION_FAILED"),
    STORAGE_DEGRADATION("STORAGE_DEGRADATION");

    private final String value;

    AuditAction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
