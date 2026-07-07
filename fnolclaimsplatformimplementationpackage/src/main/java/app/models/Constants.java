// Feature: Internal Case Management:decision:aggregation
package app.constants;

/**
 * NFR: gdpr, soc2, nfr_section
 * Centralized constants to avoid magic strings and ensure consistent audit/traceability fields.
 */
public final class InfraConstants {
    private InfraConstants() {}

    public static final String TABLE_ADMIN_BACKEND = "admin-backend_table";
    public static final String TABLE_AUDIT_LOGGER = "audit-logger_table";
    public static final String TABLE_RULES_ENGINE = "rules-engine_table";
    public static final String BUCKET_DOCUMENT_STORAGE = "document-storage-bucket";
    public static final String OBJECT_KEY_PATTERN = "document-storage/%s.json";
    public static final String PARTITION_KEY = "pk";
    public static final String SORT_KEY = "sk";
    public static final String FIELD_ID = "id";
    public static final String FIELD_PAYLOAD = "payload";
    public static final String AUDIT_ACTION_AGGREGATION = "DECISION_AGGREGATION";
    public static final String AUDIT_ACTION_SAVE = "DECISION_PERSISTENCE";
}
