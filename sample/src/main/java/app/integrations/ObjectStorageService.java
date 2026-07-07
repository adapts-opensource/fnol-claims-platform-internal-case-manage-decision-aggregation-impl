package app.integrations;

import java.nio.file.Path;

/**
 * Object storage facade (S3 / MinIO). Reads endpoint and credentials from environment.
 */
public class ObjectStorageService {

    private final String endpoint;
    private final String bucket;

    public ObjectStorageService() {
        this(
            app.config.AppConfig.get("OBJECT_STORAGE_ENDPOINT", "http://127.0.0.1:9000"),
            app.config.AppConfig.get("OBJECT_STORAGE_BUCKET", "artifacts")
        );
    }

    public ObjectStorageService(String endpoint, String bucket) {
        this.endpoint = endpoint;
        this.bucket = bucket;
    }

    public String getBucketName(String filePurpose) {
        return bucket;
    }

    public void upload(Path localPath, String objectKey) {
        throw new UnsupportedOperationException(
            "Wire ObjectStorageService.upload to MinIO/S3 client for key: " + objectKey
        );
    }

    public byte[] download(String objectKey) {
        throw new UnsupportedOperationException(
            "Wire ObjectStorageService.download to MinIO/S3 client for key: " + objectKey
        );
    }
}
