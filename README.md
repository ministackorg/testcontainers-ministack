<p align="center">
  <img src="ministack_logo.png" alt="MiniStack" width="300"/>
</p>

<h1 align="center">Testcontainers MiniStack</h1>
<p align="center">Official <a href="https://www.testcontainers.org/">Testcontainers</a> module for <a href="https://ministack.org">MiniStack</a> — free, open-source AWS emulator.</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/org.ministack/testcontainers-ministack"><img src="https://img.shields.io/maven-central/v/org.ministack/testcontainers-ministack" alt="Maven Central"></a>
  <a href="https://github.com/ministackorg/testcontainers-ministack/blob/main/LICENSE"><img src="https://img.shields.io/github/license/ministackorg/testcontainers-ministack" alt="License"></a>
  <a href="https://github.com/ministackorg/ministack"><img src="https://img.shields.io/badge/MiniStack-41%20AWS%20services-blue" alt="MiniStack"></a>
</p>

---

## Java

[![Maven Central](https://img.shields.io/maven-central/v/org.ministack/testcontainers-ministack)](https://central.sonatype.com/artifact/org.ministack/testcontainers-ministack)

### Installation

**Maven**

```xml
<dependency>
  <groupId>org.ministack</groupId>
  <artifactId>testcontainers-ministack</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

**Gradle**

```groovy
testImplementation 'org.ministack:testcontainers-ministack:0.1.0'
```

### Quick start

```java
try(MiniStackContainer ministack = new MiniStackContainer()){
    ministack.start();
    String endpoint = ministack.getEndpoint();

    S3Client s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(ministack.getRegion())
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ministack.getAccessKey(), ministack.getSecretKey())))
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

### Specific version

```java
// Pin to a specific MiniStack release
MiniStackContainer ministack = new MiniStackContainer("1.2.5");
```

### Real Infrastructure
```java
try(MiniStackContainer ministack = new MiniStackContainer()){
    ministack.withRealInfrastructure();
    ministack.start();
    String endpoint = ministack.getEndpoint();

    RdsClient rds = RdsClient.builder().endpointOverride(endpoint()).region(region)
            .credentialsProvider(creds).build();

    CreateDbInstanceResponse createDbInstanceResponse = rds.createDBInstance(b -> b
            .dbInstanceIdentifier("postgres")
            .dbInstanceClass("db.t3.micro")
            .engine("postgres")
            .masterUsername("admin")
            .masterUserPassword("password")
            .dbName("postgresdb")
            .allocatedStorage(20)
    );
    
    // Ministack will create and start a postgres docker container
    // Before you connect to postgres you have to wait until the container is started
}
```

### What you get

- All 41 AWS services on a single container
- Health check waits for readiness automatically
- `getEndpoint()` returns the mapped URL for SDK configuration
- Works with any AWS SDK (Java, Go, Python, Node.js)
- Real database containers (RDS, ElastiCache) when Docker socket is mounted

## Other languages

Coming when requested. Open an issue or upvote [ministackorg/ministack](https://github.com/ministackorg/ministack/issues/250).

| Language | Status                         |
|----------|--------------------------------|
| Java     | **Published** on Maven Central |
| Go       | Planned                        |
| Python   | Planned                        |
| .NET     | Planned                        |

## License

MIT
