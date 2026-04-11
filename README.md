# Testcontainers MiniStack

Testcontainers modules for [MiniStack](https://ministack.org) — free, open-source AWS emulator.

## Java

### Installation

```xml
<dependency>
    <groupId>org.ministack</groupId>
    <artifactId>testcontainers-ministack</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

### Usage

```java
try (MiniStackContainer ministack = new MiniStackContainer()) {
    ministack.start();
    String endpoint = ministack.getEndpoint();

    S3Client s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
            .forcePathStyle(true)
            .build();

    s3.createBucket(b -> b.bucket("my-bucket"));
}
```

### Spring Boot

```java
@Bean
@ServiceConnection
public MiniStackContainer miniStackContainer() {
    return new MiniStackContainer("latest");
}
```

## Other languages

Coming when requested. See [ministackorg/ministack#250](https://github.com/ministackorg/ministack/issues/250).

## License

MIT
