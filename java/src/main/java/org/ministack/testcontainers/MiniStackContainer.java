package org.ministack.testcontainers;

import org.testcontainers.DockerClientFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

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
}
