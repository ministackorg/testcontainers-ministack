package org.ministack.testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.testcontainers.DockerClientFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
     * @return endpoint URL (e.g. "http://127.0.0.1:32789")
     */
    public String getEndpoint() {
        try {
            final String address = getHost();
            String ipAddress = InetAddress.getByName(address).getHostAddress();
            return String.format("http://%s:%d", ipAddress, getMappedPort(PORT));
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot obtain endpoint URL", e);
        }
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
     * RDS spins up actual Postgres/MySQL containers, ElastiCache spins up real Redis, Athena runs real SQL via DuckDB, ECS runs real Docker containers.
     * @return this container instance for chaining
     */
    public MiniStackContainer withRealInfrastructure() {
        return withFileSystemBind(DockerClientFactory.instance().getRemoteDockerUnixSocketPath(), "/var/run/docker.sock");
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
