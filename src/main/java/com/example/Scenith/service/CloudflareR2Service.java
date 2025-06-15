package com.example.Scenith.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

@Service
@Profile("!test")
@RequiredArgsConstructor
public class CloudflareR2Service {
    private static final Logger logger = LoggerFactory.getLogger(CloudflareR2Service.class);
    private static final long LARGE_FILE_THRESHOLD = 200 * 1024 * 1024; // 200 MB

    private S3Client s3Client;
    private S3AsyncClient s3AsyncClient;
    private S3Presigner s3Presigner;
    private S3TransferManager transferManager;

    @Value("${r2.access.key.id}")
    private String accessKeyId;

    @Value("${r2.secret.access.key}")
    private String secretAccessKey;

    @Value("${r2.bucket.name}")
    private String bucketName;

    @Value("${r2.endpoint}")
    private String endpoint;

    @Value("${r2.region}")
    private String region;

    @Value("${cf.public.access.url:https://cdn.scenith.in}")
    private String cdnDomain;

    @PostConstruct
    public void init() {
        logger.info("Initializing CloudflareR2Service with Access Key ID: {}, Bucket: {}, CDN Domain: {}", accessKeyId, bucketName, cdnDomain);

        if (accessKeyId == null || accessKeyId.isBlank() ||
                secretAccessKey == null || secretAccessKey.isBlank() ||
                bucketName == null || bucketName.isBlank() ||
                endpoint == null || endpoint.isBlank() ||
                cdnDomain == null || cdnDomain.isBlank()) {
            logger.error("Cloudflare R2 credentials or configuration are missing or blank");
            throw new IllegalStateException("Cloudflare R2 configuration cannot be blank");
        }

        // Initialize credentials
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        // Initialize S3 synchronous client
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(region))
                .build();

        // Initialize S3 asynchronous client
        this.s3AsyncClient = S3AsyncClient.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(region))
                .build();

        // Initialize S3 presigner
        this.s3Presigner = S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(java.net.URI.create(endpoint))
                .region(Region.of(region))
                .build();

        // Initialize TransferManager with S3AsyncClient
        this.transferManager = S3TransferManager.builder()
                .s3Client(s3AsyncClient)
                .build();

        // Verify bucket existence
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            logger.info("Bucket '{}' exists and is accessible", bucketName);
        } catch (Exception e) {
            logger.error("Failed to verify bucket '{}': {}", bucketName, e.getMessage());
            throw new RuntimeException("Failed to initialize Cloudflare R2 client", e);
        }
    }

    public File downloadFile(String r2Path, String destinationPath) throws IOException {
        try {
            if (!fileExists(r2Path)) {
                logger.error("File does not exist in R2: {}/{}", bucketName, r2Path);
                throw new IOException("File not found in R2: " + r2Path);
            }

            File destinationFile = new File(destinationPath);
            Path destinationPathObj = destinationFile.toPath();
            Files.createDirectories(destinationPathObj.getParent());

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(r2Path)
                    .build();

            s3Client.getObject(getObjectRequest, destinationPathObj);

            // Verify the downloaded file
            if (!destinationFile.exists() || !destinationFile.isFile() || destinationFile.length() == 0) {
                logger.error("Downloaded file is invalid: path={}, exists={}, isFile={}, size={}",
                        destinationPath, destinationFile.exists(), destinationFile.isFile(), destinationFile.length());
                throw new IOException("Downloaded file is invalid: " + destinationPath);
            }

            logger.info("Downloaded file from R2: {}/{} to {} (size: {} bytes)",
                    bucketName, r2Path, destinationPath, destinationFile.length());
            return destinationFile;
        } catch (S3Exception e) {
            logger.error("S3 error downloading file from R2: {}/{}, code: {}, message: {}",
                    bucketName, r2Path, e.awsErrorDetails().errorCode(), e.getMessage());
            throw new IOException("Failed to download file from R2: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error downloading file from R2: {}/{}, message: {}",
                    bucketName, r2Path, e.getMessage());
            throw new IOException("Failed to download file from R2", e);
        }
    }

    /**
     * Enhanced method to wait for file availability with better retry logic
     */
    public boolean waitForFileAvailability(String r2Path, int maxRetries, int initialDelayMs) {
        int attempt = 0;
        int delay = initialDelayMs;

        while (attempt < maxRetries) {
            try {
                if (fileExists(r2Path)) {
                    // Double-check by trying to get object metadata
                    HeadObjectRequest headRequest = HeadObjectRequest.builder()
                            .bucket(bucketName)
                            .key(r2Path)
                            .build();
                    HeadObjectResponse response = s3Client.headObject(headRequest);

                    if (response.contentLength() > 0) {
                        logger.info("File is available in R2: {}, size: {} bytes", r2Path, response.contentLength());
                        return true;
                    }
                }
            } catch (Exception e) {
                logger.debug("File availability check failed on attempt {}: {}", attempt + 1, e.getMessage());
            }

            attempt++;
            if (attempt < maxRetries) {
                try {
                    logger.debug("Waiting {}ms before retry {}/{} for file: {}", delay, attempt + 1, maxRetries, r2Path);
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, 10000); // Exponential backoff, max 10 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while waiting for file availability: {}", r2Path);
                    return false;
                }
            }
        }

        logger.warn("File not available after {} retries: {}", maxRetries, r2Path);
        return false;
    }

    /**
     * Enhanced download with retry logic
     */
    public File downloadFileWithRetry(String r2Path, String destinationPath, int maxRetries) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return downloadFile(r2Path, destinationPath);
            } catch (IOException e) {
                lastException = e;
                logger.warn("Download attempt {}/{} failed for {}: {}", attempt, maxRetries, r2Path, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000 * attempt); // Progressive delay
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted", ie);
                    }
                }
            }
        }

        throw new IOException("Failed to download after " + maxRetries + " attempts: " + r2Path, lastException);
    }

    public File saveMultipartFileToTemp(MultipartFile file, String tempPath) throws IOException {
        if (file == null || file.isEmpty()) {
            logger.error("MultipartFile is null or empty for tempPath: {}", tempPath);
            throw new IllegalArgumentException("MultipartFile is null or empty");
        }

        try {
            Path tempFilePath = Path.of(tempPath);
            Files.createDirectories(tempFilePath.getParent());

            if (!Files.isWritable(tempFilePath.getParent())) {
                logger.error("Parent directory is not writable: {}", tempFilePath.getParent());
                throw new IOException("Cannot write to directory: " + tempFilePath.getParent());
            }

            File tempFile = tempFilePath.toFile();
            logger.debug("Saving MultipartFile to temp file: {}", tempFile.getAbsolutePath());
            file.transferTo(tempFile);
            logger.info("Successfully saved MultipartFile to temp: {}", tempFile.getAbsolutePath());
            return tempFile;
        } catch (IOException e) {
            logger.error("Failed to save MultipartFile to temp: {}, error: {}", tempPath, e.getMessage());
            throw e;
        }
    }

    public void uploadFile(String r2Path, File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            logger.error("Invalid file for upload to R2 path: {}, file: {}", r2Path, file);
            throw new IllegalArgumentException("File is null, does not exist, or is not a file");
        }

        try {
            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(r2Path)
                    .contentType(contentType)
                    .cacheControl("max-age=2592000") // Cache for 1 month
                    .build();

            if (file.length() > LARGE_FILE_THRESHOLD) {
                logger.debug("Using TransferManager for large file upload: {}", file.getName());
                FileUpload fileUpload = transferManager.uploadFile(UploadFileRequest.builder()
                        .putObjectRequest(putObjectRequest)
                        .source(file.toPath())
                        .build());
                fileUpload.completionFuture().join();
            } else {
                s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
            }

            logger.info("Successfully uploaded file to R2: bucket={}, path={}", bucketName, r2Path);
        } catch (Exception e) {
            logger.error("Failed to upload file to R2: bucket={}, path={}, error: {}", bucketName, r2Path, e.getMessage());
            throw new IOException("Failed to upload file to R2", e);
        }
    }

    public String uploadFile(MultipartFile file, String r2Path) throws IOException {
        if (file == null || file.isEmpty()) {
            logger.error("MultipartFile is null or empty for R2 path: {}", r2Path);
            throw new IllegalArgumentException("MultipartFile is null or empty");
        }

        try {
            String tempFileName = "r2-upload-" + System.currentTimeMillis() + "-" + file.getOriginalFilename();
            String tempPath = System.getProperty("java.io.tmpdir") + File.separator + "videoeditor" + File.separator + tempFileName;
            File tempFile = saveMultipartFileToTemp(file, tempPath);
            try {
                uploadFile(r2Path, tempFile);
                return r2Path;
            } finally {
                try {
                    if (tempFile.exists()) {
                        Files.delete(tempFile.toPath());
                        logger.debug("Deleted temporary file: {}", tempFile.getAbsolutePath());
                    }
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary file: {}, error: {}", tempFile.getAbsolutePath(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to upload MultipartFile to R2: path={}, error={}", r2Path, e.getMessage());
            throw new IOException("Failed to upload MultipartFile to R2", e);
        }
    }

    public String uploadFile(File file, String r2Path) throws IOException {
        uploadFile(r2Path, file);
        return r2Path;
    }

    public void deleteFile(String r2Path) throws IOException {
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(r2Path)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            logger.debug("Deleted file from R2: bucket={}, path={}", bucketName, r2Path);
        } catch (Exception e) {
            logger.error("Failed to delete file from R2: path={}, error={}", r2Path, e.getMessage());
            throw new IOException("Failed to delete file from R2", e);
        }
    }

    public void deleteDirectory(String prefix) throws IOException {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();
            ListObjectsV2Response listResponse;
            do {
                listResponse = s3Client.listObjectsV2(listRequest);
                for (S3Object s3Object : listResponse.contents()) {
                    DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Object.key())
                            .build();
                    s3Client.deleteObject(deleteObjectRequest);
                    logger.debug("Deleted file from R2: bucket={}, path={}", bucketName, s3Object.key());
                }
                listRequest = listRequest.toBuilder().continuationToken(listResponse.nextContinuationToken()).build();
            } while (listResponse.isTruncated());
            logger.debug("Deleted R2 directory: bucket={}, prefix={}", bucketName, prefix);
        } catch (Exception e) {
            logger.error("Failed to delete R2 directory: prefix={}, error={}", prefix, e.getMessage());
            throw new IOException("Failed to delete R2 directory", e);
        }
    }

    public boolean fileExists(String r2Path) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(r2Path)
                    .build();
            s3Client.headObject(headObjectRequest);
            logger.debug("File exists in R2: bucket={}, path={}", bucketName, r2Path);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                logger.debug("File does not exist in R2: bucket={}, path={}", bucketName, r2Path);
                return false;
            }
            logger.error("Error checking file existence in R2: path={}, error={}", r2Path, e.getMessage());
            throw e;
        }
    }

    public String generateDownloadUrl(String r2Path, long expirationSeconds) {
        try {
            if (!fileExists(r2Path)) {
                logger.error("File does not exist in R2 for URL generation: {}/{}", bucketName, r2Path);
                throw new IOException("File not found in R2: " + r2Path);
            }
            // Remove protocol from cdnDomain if present
            String cleanCdnDomain = cdnDomain.replaceFirst("^(https?://)", "");
            // Use public CDN URL
            String publicUrl = String.format("https://%s/%s", cleanCdnDomain, r2Path);
            logger.info("Generated public CDN URL for R2 path: {}", publicUrl);
            return publicUrl;
        } catch (Exception e) {
            logger.error("Failed to generate download URL for path: {}, error: {}", r2Path, e.getMessage());
            throw new RuntimeException("Failed to generate download URL", e);
        }
    }

    public String generatePresignedUrl(String r2Path, long expirationSeconds) throws IOException {
        try {
            if (!fileExists(r2Path)) {
                logger.error("File does not exist in R2 for presigned URL: {}/{}", bucketName, r2Path);
                throw new IOException("File not found in R2: " + r2Path);
            }

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expirationSeconds))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(r2Path)
                            .build())
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();
            logger.info("Generated pre-signed URL for R2 path: {}", r2Path);
            return url;
        } catch (Exception e) {
            logger.error("Failed to generate presigned URL for path: {}, error: {}", r2Path, e.getMessage());
            throw new IOException("Failed to generate presigned URL", e);
        }
    }

    /**
     * Generates both CDN and presigned URLs for a file.
     * @param r2Path The R2 path of the file.
     * @param expirationSeconds Duration for presigned URL validity.
     * @return Map containing "cdnUrl" and "presignedUrl".
     * @throws IOException If the file doesn't exist or URL generation fails.
     */
    public Map<String, String> generateUrls(String r2Path, long expirationSeconds) throws IOException {
        Map<String, String> urls = new HashMap<>();
        if (!fileExists(r2Path)) {
            logger.error("File does not exist in R2 for URL generation: {}/{}", bucketName, r2Path);
            throw new IOException("File not found in R2: " + r2Path);
        }

        // Generate CDN URL
        String cleanCdnDomain = cdnDomain.replaceFirst("^(https?://)", "");
        String cdnUrl = String.format("https://%s/%s", cleanCdnDomain, r2Path);
        urls.put("cdnUrl", cdnUrl);

        // Generate presigned URL
        String presignedUrl = generatePresignedUrl(r2Path, expirationSeconds);
        urls.put("presignedUrl", presignedUrl);

        logger.info("Generated URLs for R2 path: {} - CDN: {}, Presigned: {}", r2Path, cdnUrl, presignedUrl);
        return urls;
    }

    /**
     * Checks if a file is available via its CDN URL.
     * @param cdnUrl The CDN URL to check.
     * @return true if the file is accessible, false otherwise.
     */
    public boolean isCdnUrlAvailable(String cdnUrl) {
        try {
            URL url = new URL(cdnUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(2000); // 2 seconds timeout
            connection.setReadTimeout(2000);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            boolean available = responseCode == 200;
            logger.debug("CDN URL availability check: {} - Available: {}", cdnUrl, available);
            return available;
        } catch (Exception e) {
            logger.warn("Failed to check CDN URL availability: {}, error: {}", cdnUrl, e.getMessage());
            return false;
        }
    }
}