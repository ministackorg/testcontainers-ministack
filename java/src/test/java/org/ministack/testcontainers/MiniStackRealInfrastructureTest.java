package org.ministack.testcontainers;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import redis.clients.jedis.Jedis;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheNode;
import software.amazon.awssdk.services.elasticache.model.CreateCacheClusterResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.Endpoint;

import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLNonTransientConnectionException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MiniStackRealInfrastructureTest {

    static MiniStackContainer ministack = new MiniStackContainer("latest");
    static StaticCredentialsProvider creds;
    static Region region;

    @BeforeAll
    static void startContainer() {
        ministack.withRealInfrastructure();
        ministack.start();
        creds = StaticCredentialsProvider
                .create(AwsBasicCredentials.create(ministack.getAccessKey(), ministack.getSecretKey()));
        region = Region.of(ministack.getRegion());
    }

    @AfterAll
    static void stopContainer() {
        ministack.stop();
    }

    private URI endpoint() {
        return URI.create(ministack.getEndpoint());
    }

    @Test
    @Order(1)
    void containerIsRunning() {
        assertTrue(ministack.isRunning());
        assertNotNull(ministack.getEndpoint());
        assertTrue(ministack.getPort() > 0);
        assertNotNull(ministack.getAccessKey());
        assertNotNull(ministack.getSecretKey());
        assertNotNull(ministack.getRegion());
    }

    // -----------------------------------------------------------------------
    // RDS (real infrastructure)
    // -----------------------------------------------------------------------


    @Test
    void rdsCreateDbInstancePostgres() {
        RdsClient rds = RdsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String username = "admin";
        String password = "password";
        String dbName = "postgresdb";
        CreateDbInstanceResponse createDbInstanceResponse = rds.createDBInstance(b -> b
                .dbInstanceIdentifier("postgres")
                .dbInstanceClass("db.t3.micro")
                .engine("postgres")
                .masterUsername(username)
                .masterUserPassword(password)
                .dbName(dbName)
                .allocatedStorage(20)
        );
        Endpoint dbEndpoint = createDbInstanceResponse.dbInstance().endpoint();
        assertNotNull(dbEndpoint);

        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", dbEndpoint.address(), dbEndpoint.port(), dbName);

        // We have to wait here until db container ist started
        Awaitility.given().ignoreException(PSQLException.class)
                .await().atMost(Duration.ofSeconds(10))
                .until(() -> DriverManager.getConnection(jdbcUrl, username, password).isValid(10));

    }

    @Test
    void rdsCreateDbInstanceAuroraPostgres() {
        RdsClient rds = RdsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String username = "admin";
        String password = "password";
        String dbName = "aurora-postgres-db";
        CreateDbInstanceResponse createDbInstanceResponse = rds.createDBInstance(b -> b
                .dbInstanceIdentifier("aurora-postgresql")
                .dbInstanceClass("db.t3.micro")
                .engine("aurora-postgresql")
                .masterUsername(username)
                .masterUserPassword(password)
                .dbName(dbName)
                .allocatedStorage(20)
        );
        Endpoint dbEndpoint = createDbInstanceResponse.dbInstance().endpoint();
        assertNotNull(dbEndpoint);

        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", dbEndpoint.address(), dbEndpoint.port(), dbName);

        // We have to wait here until db container ist started
        Awaitility.given().ignoreException(PSQLException.class)
                .await().atMost(Duration.ofSeconds(10))
                .until(() -> DriverManager.getConnection(jdbcUrl, username, password).isValid(10));
    }

    @Test
    void rdsCreateDbInstanceMysql() {
        RdsClient rds = RdsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String username = "admin";
        String password = "password";
        String dbName = "mysqldb";
        CreateDbInstanceResponse createDbInstanceResponse = rds.createDBInstance(b -> b
                .dbInstanceIdentifier("mysql")
                .dbInstanceClass("db.t3.micro")
                .engine("mysql")
                .masterUsername(username)
                .masterUserPassword(password)
                .dbName(dbName)
                .allocatedStorage(20)
        );
        Endpoint dbEndpoint = createDbInstanceResponse.dbInstance().endpoint();
        assertNotNull(dbEndpoint);

        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s", dbEndpoint.address(), dbEndpoint.port(), dbName);

        // We have to wait here until db container ist started
        Awaitility.given().ignoreException(CommunicationsException.class)
                .await().atMost(Duration.ofSeconds(20))
                .until(() -> DriverManager.getConnection(jdbcUrl, username, password).isValid(10));
    }

    @Test
    void rdsCreateDbInstanceAuroraMysql() {
        RdsClient rds = RdsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String username = "admin";
        String password = "password";
        String dbName = "aurora-mysql-db";
        CreateDbInstanceResponse createDbInstanceResponse = rds.createDBInstance(b -> b
                .dbInstanceIdentifier("aurora-mysql")
                .dbInstanceClass("db.t3.micro")
                .engine("aurora-mysql")
                .masterUsername(username)
                .masterUserPassword(password)
                .dbName(dbName)
                .allocatedStorage(20)
        );
        Endpoint dbEndpoint = createDbInstanceResponse.dbInstance().endpoint();
        assertNotNull(dbEndpoint);

        String jdbcUrl = String.format("jdbc:mysql://%s:%s/%s", dbEndpoint.address(), dbEndpoint.port(), dbName);

        // We have to wait here until db container ist started
        Awaitility.given().ignoreException(CommunicationsException.class)
                .await().atMost(Duration.ofSeconds(20))
                .until(() -> DriverManager.getConnection(jdbcUrl, username, password).isValid(10));
    }

    @Test
    void rdsCreateDbInstanceMariaDb() {
        RdsClient rds = RdsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String username = "admin";
        String password = "password";
        String dbName = "mariadb";
        CreateDbInstanceResponse createDbInstanceResponse = rds.createDBInstance(b -> b
                .dbInstanceIdentifier("mariadb")
                .dbInstanceClass("db.t3.micro")
                .engine("mariadb")
                .masterUsername(username)
                .masterUserPassword(password)
                .dbName(dbName)
                .allocatedStorage(20)
        );
        Endpoint dbEndpoint = createDbInstanceResponse.dbInstance().endpoint();
        assertNotNull(dbEndpoint);

        String jdbcUrl = String.format("jdbc:mariadb://%s:%s/%s", dbEndpoint.address(), dbEndpoint.port(), dbName);

        // We have to wait here until db container ist started
        Awaitility.given().ignoreException(SQLNonTransientConnectionException.class)
                .await()
                .atMost(Duration.ofSeconds(20))
                .until(() -> DriverManager.getConnection(jdbcUrl, username, password).isValid(10));
    }

    // -----------------------------------------------------------------------
    // ElastiCache (real infrastructure)
    // -----------------------------------------------------------------------

    @Test
    void elasticacheCreatecacheCluster() {
        ElastiCacheClient elastiCache = ElastiCacheClient.builder()
                .endpointOverride(endpoint())
                .region(region)
                .credentialsProvider(creds)
                .build();

        String cacheClusterId = "tc-redis";
        String redisKey = "key";
        String redisValue = "value";

        CreateCacheClusterResponse res = elastiCache.createCacheCluster(b -> b
                .cacheClusterId(cacheClusterId)
                .engine("redis")
                .cacheNodeType("cache.t3.micro")
                .numCacheNodes(1)
        );

        assertEquals(cacheClusterId, res.cacheCluster().cacheClusterId());
        assertEquals("redis", res.cacheCluster().engine());
        assertEquals(1, res.cacheCluster().numCacheNodes());

        CacheNode cacheNode = res.cacheCluster().cacheNodes().get(0);
        String redisHost = cacheNode.endpoint().address();
        int redisPort = cacheNode.endpoint().port();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .ignoreExceptions()
                .until(() -> {
                    Jedis redis = new Jedis(redisHost, redisPort);
                    redis.set(redisKey, redisValue);
                    return redisValue.equals(redis.get(redisKey));
                });
    }
}
