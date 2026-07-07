package app.config;

public final class AppConfig {
    private AppConfig() {}

    public static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
