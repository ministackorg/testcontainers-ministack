package org.ministack.testcontainers;

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
}
