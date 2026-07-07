package app.integrations;

import java.util.List;
import java.util.Map;

/**
 * Tabular data facade (DynamoDB / Cassandra). Table name from constructor or config.
 */
public class TabularDataService {

    private final String tableName;

    public TabularDataService(String tableName) {
        this.tableName = tableName;
    }

    public TabularDataService() {
        this(app.config.AppConfig.get("TABULAR_TABLE_NAME", "records"));
    }

    public Map<String, Object> getItem(String partitionKey, String sortKey) {
        throw new UnsupportedOperationException(
            "Wire TabularDataService.getItem for table: " + tableName
        );
    }

    public void putItem(Map<String, Object> item) {
        throw new UnsupportedOperationException(
            "Wire TabularDataService.putItem for table: " + tableName
        );
    }

    public List<Map<String, Object>> query(String partitionKey) {
        throw new UnsupportedOperationException(
            "Wire TabularDataService.query for table: " + tableName
        );
    }
}
