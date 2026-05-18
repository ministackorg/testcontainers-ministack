package org.ministack.testcontainers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-config tests for {@link MiniStackContainer}'s builder methods.
 *
 * <p>No Docker required — each test constructs a fresh container, calls the
 * builder, then inspects {@code getEnvMap()} directly. The MiniStack image is
 * never pulled and {@code start()} is never invoked.
 */
class MiniStackContainerBuilderTest {

    @Test
    void withRegionSetsMiniStackRegionEnv() {
        MiniStackContainer c = new MiniStackContainer("latest").withRegion("eu-west-1");
        assertEquals("eu-west-1", c.getEnvMap().get("MINISTACK_REGION"));
        assertEquals("eu-west-1", c.getRegion());
    }

    @Test
    void withRegionReturnsSameInstanceForChaining() {
        MiniStackContainer c = new MiniStackContainer("latest");
        assertSame(c, c.withRegion("ap-south-1"));
    }

    @Test
    void withCredentialsSetsBothAwsEnvVars() {
        MiniStackContainer c = new MiniStackContainer("latest")
            .withCredentials("AKIA000000000000TEST", "secret-xyz");
        Map<String, String> env = c.getEnvMap();
        assertEquals("AKIA000000000000TEST", env.get("AWS_ACCESS_KEY_ID"));
        assertEquals("secret-xyz", env.get("AWS_SECRET_ACCESS_KEY"));
        assertEquals("AKIA000000000000TEST", c.getAccessKey());
        assertEquals("secret-xyz", c.getSecretKey());
    }

    @Test
    void withPersistenceSetsBothPersistEnvVars() {
        MiniStackContainer c = new MiniStackContainer("latest").withPersistence();
        Map<String, String> env = c.getEnvMap();
        assertEquals("1", env.get("PERSIST_STATE"));
        assertEquals("1", env.get("S3_PERSIST"));
    }

    @Test
    void withImdsV2RequiredSetsTheEnvVar() {
        MiniStackContainer c = new MiniStackContainer("latest").withImdsV2Required();
        assertEquals("1", c.getEnvMap().get("MINISTACK_IMDS_V2_REQUIRED"));
    }

    @Test
    void buildersChainCleanly() {
        MiniStackContainer c = new MiniStackContainer("1.3.42")
            .withRegion("eu-west-2")
            .withCredentials("AKIAEXAMPLE000000000", "supersecret")
            .withPersistence()
            .withImdsV2Required();
        Map<String, String> env = c.getEnvMap();
        assertEquals("eu-west-2", env.get("MINISTACK_REGION"));
        assertEquals("AKIAEXAMPLE000000000", env.get("AWS_ACCESS_KEY_ID"));
        assertEquals("supersecret", env.get("AWS_SECRET_ACCESS_KEY"));
        assertEquals("1", env.get("PERSIST_STATE"));
        assertEquals("1", env.get("S3_PERSIST"));
        assertEquals("1", env.get("MINISTACK_IMDS_V2_REQUIRED"));
    }

    @Test
    void getMiniStackVersionReturnsTagWhenPinned() {
        MiniStackContainer c = new MiniStackContainer("1.3.42");
        assertEquals("1.3.42", c.getMiniStackVersion());
    }

    @Test
    void getMiniStackVersionReturnsLatestForUnpinnedConstructor() {
        MiniStackContainer c = new MiniStackContainer();
        assertEquals("latest", c.getMiniStackVersion());
    }

    @Test
    void defaultsAreUnchangedWhenNoBuildersCalled() {
        MiniStackContainer c = new MiniStackContainer("latest");
        // No env vars touched — getters fall back to the documented defaults.
        assertEquals("test", c.getAccessKey());
        assertEquals("test", c.getSecretKey());
        assertEquals("us-east-1", c.getRegion());
        // And our builder-driven env vars are absent.
        Map<String, String> env = c.getEnvMap();
        assertTrue(env.get("PERSIST_STATE") == null || env.get("PERSIST_STATE").isEmpty());
        assertTrue(env.get("S3_PERSIST") == null || env.get("S3_PERSIST").isEmpty());
        assertTrue(env.get("MINISTACK_IMDS_V2_REQUIRED") == null
            || env.get("MINISTACK_IMDS_V2_REQUIRED").isEmpty());
    }
}
