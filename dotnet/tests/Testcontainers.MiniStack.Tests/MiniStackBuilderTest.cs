namespace Testcontainers.MiniStack.Tests;

/// <summary>
/// Pure-configuration tests for the builder options, modelled on the Java
/// <c>MiniStackContainerBuilderTest</c>. Each test builds a container and inspects
/// its configuration; nothing is started.
/// </summary>
public sealed class MiniStackBuilderTest
{
    private static IReadOnlyDictionary<string, string> Env(MiniStackBuilder builder)
    {
        return builder.Build().Configuration.Environments;
    }

    [Fact]
    public void WithRegionSetsMiniStackRegionEnv()
    {
        var container = new MiniStackBuilder().WithRegion("eu-west-1").Build();
        Assert.Equal("eu-west-1", container.Configuration.Environments["MINISTACK_REGION"]);
        Assert.Equal("eu-west-1", container.GetRegion());
    }

    [Fact]
    public void WithCredentialsSetsBothAwsEnvVars()
    {
        var container = new MiniStackBuilder().WithCredentials("AKIA000000000000TEST", "secret-xyz").Build();
        var env = container.Configuration.Environments;
        Assert.Equal("AKIA000000000000TEST", env["AWS_ACCESS_KEY_ID"]);
        Assert.Equal("secret-xyz", env["AWS_SECRET_ACCESS_KEY"]);
        Assert.Equal("AKIA000000000000TEST", container.GetAccessKey());
        Assert.Equal("secret-xyz", container.GetSecretKey());
    }

    [Fact]
    public void WithPersistenceSetsBothPersistEnvVars()
    {
        var env = Env(new MiniStackBuilder().WithPersistence());
        Assert.Equal("1", env["PERSIST_STATE"]);
        Assert.Equal("1", env["S3_PERSIST"]);
    }

    [Fact]
    public void WithImdsV2RequiredSetsTheEnvVar()
    {
        Assert.Equal("1", Env(new MiniStackBuilder().WithImdsV2Required())["MINISTACK_IMDS_V2_REQUIRED"]);
    }

    [Fact]
    public void BuildersChainCleanly()
    {
        var env = Env(new MiniStackBuilder("ministackorg/ministack:1.3.42")
            .WithRegion("eu-west-2")
            .WithCredentials("AKIAEXAMPLE000000000", "supersecret")
            .WithPersistence()
            .WithImdsV2Required());
        Assert.Equal("eu-west-2", env["MINISTACK_REGION"]);
        Assert.Equal("AKIAEXAMPLE000000000", env["AWS_ACCESS_KEY_ID"]);
        Assert.Equal("supersecret", env["AWS_SECRET_ACCESS_KEY"]);
        Assert.Equal("1", env["PERSIST_STATE"]);
        Assert.Equal("1", env["S3_PERSIST"]);
        Assert.Equal("1", env["MINISTACK_IMDS_V2_REQUIRED"]);
    }

    [Fact]
    public void BuilderOptionsDoNotMutateTheOriginalBuilder()
    {
        // .NET builders are immutable; the Java builder methods return the same instance instead.
        var original = new MiniStackBuilder();
        _ = original.WithRegion("ap-south-1");
        Assert.Equal("us-east-1", original.Build().GetRegion());
    }

    [Fact]
    public void DefaultsAreUnchangedWhenNoBuildersCalled()
    {
        var container = new MiniStackBuilder().Build();
        Assert.Equal("test", container.GetAccessKey());
        Assert.Equal("test", container.GetSecretKey());
        Assert.Equal("us-east-1", container.GetRegion());
        var env = container.Configuration.Environments;
        Assert.False(env.ContainsKey("PERSIST_STATE"));
        Assert.False(env.ContainsKey("S3_PERSIST"));
        Assert.False(env.ContainsKey("MINISTACK_IMDS_V2_REQUIRED"));
    }

    [Fact]
    public void DefaultImageIsMiniStackLatest()
    {
        var container = new MiniStackBuilder().Build();
        Assert.Equal("ministackorg/ministack", container.Image.Repository);
        Assert.Equal("latest", container.GetMiniStackVersion());
    }

    [Fact]
    public void GetMiniStackVersionReturnsTagWhenPinned()
    {
        Assert.Equal("1.3.42", new MiniStackBuilder("ministackorg/ministack:1.3.42").Build().GetMiniStackVersion());
    }

    [Fact]
    public void WithImageOverridesTheDefaultImage()
    {
        Assert.Equal("1.3.42", new MiniStackBuilder().WithImage("ministackorg/ministack:1.3.42").Build().GetMiniStackVersion());
    }

    [Fact]
    public void ExposesGatewayPort()
    {
        var container = new MiniStackBuilder().Build();
        Assert.Contains(container.Configuration.ExposedPorts.Keys, port => port.StartsWith("4566"));
    }

    [Fact]
    public void WithRealInfrastructureBindsTheDockerSocket()
    {
        var container = new MiniStackBuilder().WithRealInfrastructure().Build();
        var mount = Assert.Single(container.Configuration.Mounts, m => m.Target == "/var/run/docker.sock");
        Assert.Equal(MountType.Bind, mount.Type);
        Assert.Equal(AccessMode.ReadWrite, mount.AccessMode);
    }
}

/// <summary>
/// Tests that change <see cref="TestcontainersSettings" />, which is process-wide,
/// so they run on their own.
/// </summary>
[CollectionDefinition(nameof(GlobalSettingsCollection), DisableParallelization = true)]
public sealed class GlobalSettingsCollection;

[Collection(nameof(GlobalSettingsCollection))]
public sealed class MiniStackImagePrefixTest
{
    [Fact]
    public void HubImageNamePrefixIsForwardedAsMiniStackImagePrefix()
    {
        var previous = TestcontainersSettings.HubImageNamePrefix;
        try
        {
            TestcontainersSettings.HubImageNamePrefix = "registry.example.com/mirror/";
            var env = new MiniStackBuilder().Build().Configuration.Environments;
            Assert.Equal("registry.example.com/mirror/", env["MINISTACK_IMAGE_PREFIX"]);
        }
        finally
        {
            TestcontainersSettings.HubImageNamePrefix = previous;
        }
    }

    [Fact]
    public void NoImagePrefixMeansNoEnvVar()
    {
        var previous = TestcontainersSettings.HubImageNamePrefix;
        try
        {
            TestcontainersSettings.HubImageNamePrefix = null;
            Assert.False(new MiniStackBuilder().Build().Configuration.Environments.ContainsKey("MINISTACK_IMAGE_PREFIX"));
        }
        finally
        {
            TestcontainersSettings.HubImageNamePrefix = previous;
        }
    }
}

[Collection(nameof(GlobalSettingsCollection))]
public sealed class MiniStackDockerSocketPathTest : IDisposable
{
    private readonly string _previousOverride = TestcontainersSettings.DockerSocketOverride;

    public MiniStackDockerSocketPathTest()
    {
        // Rancher Desktop and Docker Desktop set an override; these tests cover the endpoint rules.
        TestcontainersSettings.DockerSocketOverride = null;
    }

    public void Dispose()
    {
        TestcontainersSettings.DockerSocketOverride = _previousOverride;
    }

    [Theory]
    [InlineData("unix:///var/run/docker.sock", "/var/run/docker.sock")]
    [InlineData("unix:///run/user/1000/docker.sock", "/run/user/1000/docker.sock")]
    [InlineData("tcp://localhost:2375", "/var/run/docker.sock")]
    public void RemoteDockerSocketPathFollowsTheEndpoint(string endpoint, string expected)
    {
        Assert.Equal(expected, MiniStackBuilder.GetRemoteDockerSocketPath(new Uri(endpoint)));
    }

    [Fact]
    public void RemoteDockerSocketPathMapsVmForwardedSocketsToTheDaemonSocket()
    {
        // Docker Desktop, Rancher Desktop and Colima forward a socket under the user's home directory.
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        Assert.Equal("/var/run/docker.sock", MiniStackBuilder.GetRemoteDockerSocketPath(new Uri("unix://" + home + "/.rd/docker.sock")));
    }

    [Fact]
    public void DockerSocketOverrideWins()
    {
        TestcontainersSettings.DockerSocketOverride = "/custom/docker.sock";
        Assert.Equal("/custom/docker.sock", MiniStackBuilder.GetRemoteDockerSocketPath(new Uri("unix:///run/user/1000/docker.sock")));
    }
}
