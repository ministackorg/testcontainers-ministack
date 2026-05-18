package org.ministack.testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.testcontainers.DockerClientFactory;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

/**
 * Testcontainers module for MiniStack — free, open-source AWS emulator.
 *
 * <p>Starts a MiniStack container exposing all AWS services on a single port.
 * Use {@link #getEndpoint()} to configure your AWS SDK client.
 *
 * <pre>{@code
 * try (MiniStackContainer ministack = new MiniStackContainer()) {
 *     ministack.start();
 *     String endpoint = ministack.getEndpoint();
 *     // configure SDK client with endpoint
 * }
 * }</pre>
 */
public class MiniStackContainer extends GenericContainer<MiniStackContainer> {

    private static final Logger LOGGER = Logger.getLogger(MiniStackContainer.class.getName());
    private static final int PORT = 4566;
    private static final String DEFAULT_AWS_ACCESS_KEY_ID = "test";
    private static final String DEFAULT_AWS_SECRET_ACCESS_KEY = "test";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final DockerImageName DEFAULT_IMAGE =
        DockerImageName.parse("ministackorg/ministack");

    private final String imageTag;

    /**
     * Create a MiniStack container with the default image and "latest" tag.
     */
    public MiniStackContainer() {
        this("latest");
    }

    /**
     * Create a MiniStack container with a specific tag.
     *
     * @param tag the Docker image tag (e.g. "latest", "1.2.4")
     */
    public MiniStackContainer(String tag) {
        this(DEFAULT_IMAGE.withTag(tag));
    }

    /**
     * Create a MiniStack container with a custom Docker image name.
     *
     * @param dockerImageName full image name including tag
     */
    public MiniStackContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        this.imageTag = dockerImageName.getVersionPart();
        withExposedPorts(PORT);
        waitingFor(Wait.forHttp("/_ministack/health").forPort(PORT).forStatusCode(200));
        propagateHubImageNamePrefix();
    }

    /**
     * Forward Testcontainers' {@code hub.image.name.prefix} into the MiniStack
     * container as {@code MINISTACK_IMAGE_PREFIX} so nested real-infra images
     * (RDS postgres/mysql/mariadb, ElastiCache redis/memcached, EKS k3s,
     * Lambda runtimes) route through the same private registry as the
     * MiniStack image itself. Without this, only the MiniStack image honors
     * the prefix and every nested pull still hits docker.io / public.ecr.aws
     * — visible in air-gapped or proxy-only environments (issue #9).
     */
    private void propagateHubImageNamePrefix() {
        final String prefix = TestcontainersConfiguration.getInstance()
            .getEnvVarOrProperty("hub.image.name.prefix", "");
        if (prefix != null && !prefix.isEmpty()) {
            withEnv("MINISTACK_IMAGE_PREFIX", prefix);
        }
    }

    /**
     * Returns the endpoint URL for connecting AWS SDK clients.
     *
     * <p>Returns the Testcontainers-reported host directly without forcing
     * DNS resolution to an IP. Rootless Podman, Colima, and reuse modes
     * can surface non-resolvable hostnames that nonetheless route correctly
     * through Docker — resolving them eagerly broke those setups.
     *
     * @return endpoint URL (e.g. "http://localhost:32789")
     */
    public String getEndpoint() {
        return String.format("http://%s:%d", getHost(), getMappedPort(PORT));
    }

    /**
     * Returns the mapped port for the MiniStack gateway.
     *
     * @return the host port mapped to 4566
     */
    public int getPort() {
        return getMappedPort(PORT);
    }

    /**
     * Activates real infrastructure mode.
     *
     * <p>RDS spins up actual Postgres/MySQL containers, ElastiCache spins up real
     * Redis, Athena runs real SQL via DuckDB, ECS runs real Docker containers.
     *
     * <p><b>Security warning:</b> this mode bind-mounts the host Docker socket
     * into the MiniStack container. Anything running inside MiniStack — including
     * arbitrary code in Lambda handlers or RDS init scripts — gains
     * root-equivalent control of the host's container engine: it can create,
     * stop, exec into, and remove <i>any</i> container on the host (not just
     * MiniStack-spawned ones), and can mount any host path into a sibling
     * container. Use only on trusted developer machines or in isolated CI
     * runners.
     *
     * @return this container instance for chaining
     */
    public MiniStackContainer withRealInfrastructure() {
        return withFileSystemBind(DockerClientFactory.instance().getRemoteDockerUnixSocketPath(), "/var/run/docker.sock");
    }

    /**
     * Override the AWS region the emulator reports to clients.
     *
     * <p>Sets {@code MINISTACK_REGION}, which is the value returned to clients
     * via {@code DescribeRegion}-style calls and used as the default region for
     * generated ARNs. Note: AWS SDK clients still need their own region
     * configured separately; this only changes the server-side default.
     *
     * @param region AWS region (e.g. "eu-west-1"); must be set before {@code start()}
     * @return this container instance for chaining
     */
    public MiniStackContainer withRegion(String region) {
        return withEnv("MINISTACK_REGION", region);
    }

    /**
     * Override the AWS credentials the emulator accepts.
     *
     * <p>MiniStack derives the account ID from the access key (a 12-digit key
     * is treated as the account ID for multi-tenant isolation). Setting custom
     * credentials lets a test exercise non-default-account behaviour without
     * passing them through every SDK call site.
     *
     * @param accessKey AWS access key id
     * @param secretKey AWS secret access key
     * @return this container instance for chaining
     */
    public MiniStackContainer withCredentials(String accessKey, String secretKey) {
        return withEnv("AWS_ACCESS_KEY_ID", accessKey)
            .withEnv("AWS_SECRET_ACCESS_KEY", secretKey);
    }

    /**
     * Enable disk persistence so state survives container restart.
     *
     * <p>Sets {@code PERSIST_STATE=1} (in-memory service state — SQS queues,
     * DynamoDB items, IAM users, Cognito pools, etc., all written to
     * {@code STATE_DIR} on shutdown and reloaded on boot) and
     * {@code S3_PERSIST=1} (S3 object bytes written to disk so large objects
     * survive too). For a fully persistent run you typically also mount a
     * named volume at {@code /var/lib/ministack} so the data outlives the
     * container itself; without that, persistence is only within the
     * container's lifetime.
     *
     * @return this container instance for chaining
     */
    public MiniStackContainer withPersistence() {
        return withEnv("PERSIST_STATE", "1").withEnv("S3_PERSIST", "1");
    }

    /**
     * Require IMDSv2 (token-based) for instance-metadata calls, mirroring AWS'
     * 2024 default for new EC2 launches.
     *
     * <p>With this enabled, IMDS GET requests without a valid
     * {@code X-aws-ec2-metadata-token} header are rejected with HTTP 401. Use
     * to verify your code paths correctly call {@code PUT /latest/api/token}
     * before any metadata read, instead of relying on the IMDSv1 fallback.
     *
     * @return this container instance for chaining
     */
    public MiniStackContainer withImdsV2Required() {
        return withEnv("MINISTACK_IMDS_V2_REQUIRED", "1");
    }

    /**
     * Returns the MiniStack version corresponding to the Docker image tag this
     * container was constructed with.
     *
     * <p>Useful for gating tests on capability:
     * <pre>{@code
     * assumeTrue(container.getMiniStackVersion().compareTo("1.3.42") >= 0,
     *     "feature requires MiniStack 1.3.42+");
     * }</pre>
     *
     * <p>Returns the tag verbatim — semver strings like {@code "1.3.42"} sort
     * lexicographically as expected, but non-semver tags ({@code "latest"},
     * {@code "1.3"}, {@code "nightly"}) won't. Pin a specific version tag in
     * the constructor when you need precise capability gating.
     *
     * @return the image tag (e.g. {@code "1.3.42"}, {@code "latest"})
     */
    public String getMiniStackVersion() {
        return imageTag;
    }

    /**
     * Returns the AWS access key ID.
     *
     * @return the access key ID
     */
    public String getAccessKey() {
        return this.getEnvMap().getOrDefault("AWS_ACCESS_KEY_ID", DEFAULT_AWS_ACCESS_KEY_ID);
    }

    /**
     * Returns the AWS secret access key.
     *
     * @return the secret access key
     */
    public String getSecretKey() {
        return this.getEnvMap().getOrDefault("AWS_SECRET_ACCESS_KEY", DEFAULT_AWS_SECRET_ACCESS_KEY);
    }

    /**
     * Returns the AWS region.
     *
     * @return the region
     */
    public String getRegion() {
        return this.getEnvMap().getOrDefault("MINISTACK_REGION", DEFAULT_REGION);
    }

    /**
     * Stops the MiniStack container and reaps any nested containers and
     * volumes it spawned for real-infrastructure services (RDS, ElastiCache,
     * ECS, EKS, Lambda). These live on the host engine and are not children
     * of the MiniStack container, so Testcontainers' Ryuk does not see them.
     * Without explicit cleanup they survive the test run — most visibly on
     * Podman, where no external reaper exists (issue #7).
     *
     * <p>Cleanup is best-effort: failures to list or remove individual
     * resources are logged but never thrown, so a stuck remove never masks
     * a test failure.
     */
    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            reapMiniStackResources();
        }
    }

    private void reapMiniStackResources() {
        final DockerClient client;
        try {
            client = DockerClientFactory.instance().client();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "MiniStack cleanup skipped — Docker client unavailable", e);
            return;
        }

        final List<String> labelFilter = new ArrayList<>();
        labelFilter.add("ministack"); // match any container carrying the `ministack` label

        try {
            List<Container> orphans = client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(labelFilter)
                .exec();
            for (Container c : orphans) {
                try {
                    client.removeContainerCmd(c.getId())
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING,
                        "Failed to remove MiniStack container " + c.getId(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to list MiniStack containers for cleanup", e);
        }

        // Named RDS volumes (`ministack-rds-<id>-data`, `-mysql`) outlive the
        // containers when --rm is not set; remove any volume carrying the
        // ministack label too.
        try {
            client.listVolumesCmd()
                .withFilter("label", Collections.singletonList("ministack"))
                .exec()
                .getVolumes()
                .forEach(v -> {
                    try {
                        client.removeVolumeCmd(v.getName()).exec();
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE,
                            "Failed to remove MiniStack volume " + v.getName(), e);
                    }
                });
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Volume cleanup skipped", e);
        }
    }
}
