package org.ministack.testcontainers;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class MiniStackContainerTest {

    @Test
    void containerStartsAndHealthCheckPasses() {
        try (MiniStackContainer ministack = new MiniStackContainer()) {
            ministack.start();

            assertTrue(ministack.isRunning());
            assertNotNull(ministack.getEndpoint());
            assertTrue(ministack.getPort() > 0);
        }
    }

    @Test
    void s3CreateBucketWorks() {
        try (MiniStackContainer ministack = new MiniStackContainer()) {
            ministack.start();

            S3Client s3 = S3Client.builder()
                    .endpointOverride(URI.create(ministack.getEndpoint()))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")))
                    .forcePathStyle(true)
                    .build();

            s3.createBucket(b -> b.bucket("test-bucket"));
            var buckets = s3.listBuckets().buckets();
            assertTrue(buckets.stream().anyMatch(b -> b.name().equals("test-bucket")));
        }
    }
}
