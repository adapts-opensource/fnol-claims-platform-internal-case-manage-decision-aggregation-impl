package app.integrations;

/**
 * Cache facade (Redis). Endpoint from environment via AppConfig.
 */
public class CacheService {

    private final String endpoint;

    public CacheService() {
        this(app.config.AppConfig.get("REDIS_ENDPOINT", "redis://127.0.0.1:6379"));
    }

    public CacheService(String endpoint) {
        this.endpoint = endpoint;
    }

    public String get(String key) {
        throw new UnsupportedOperationException(
            "Wire CacheService.get to Redis client at: " + endpoint
        );
    }

    public void set(String key, String value) {
        throw new UnsupportedOperationException(
            "Wire CacheService.set to Redis client at: " + endpoint
        );
    }
}
