package app.integrations;

public class SecretService {
    public String resolve(String name) {
        return System.getenv(name);
    }
}
