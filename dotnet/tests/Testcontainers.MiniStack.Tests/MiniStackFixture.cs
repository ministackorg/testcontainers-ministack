namespace Testcontainers.MiniStack.Tests;

/// <summary>
/// Starts one MiniStack container for every test in a class, so the suite stays fast.
/// Each test creates its own AWS resources with a unique <see cref="Uid" /> suffix.
/// </summary>
public abstract class MiniStackFixture : IAsyncLifetime
{
    protected MiniStackFixture(MiniStackBuilder builder)
    {
        Container = builder.Build();
    }

    public MiniStackContainer Container { get; }

    public AWSCredentials Credentials => new BasicAWSCredentials(Container.GetAccessKey(), Container.GetSecretKey());

    public TConfig Config<TConfig>()
        where TConfig : ClientConfig, new()
    {
        return new TConfig { ServiceURL = Container.GetEndpoint(), AuthenticationRegion = Container.GetRegion() };
    }

    public static string Uid()
    {
        return Guid.NewGuid().ToString("N")[..8];
    }

    public ValueTask InitializeAsync()
    {
        return new ValueTask(Container.StartAsync());
    }

    public ValueTask DisposeAsync()
    {
        return Container.DisposeAsync();
    }
}

/// <summary>
/// A MiniStack container on the default <c>latest</c> image.
/// </summary>
public sealed class DefaultMiniStackFixture() : MiniStackFixture(new MiniStackBuilder());
