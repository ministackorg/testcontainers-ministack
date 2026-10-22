namespace Testcontainers.MiniStack.Tests;

public sealed class PinnedMiniStackFixture() : MiniStackFixture(new MiniStackBuilder(MiniStackBuilder.MiniStackImageRepository + ":" + PinnedMiniStackFixture.Version))
{
    public const string Version = "1.5.30";
}

/// <summary>
/// Starts a pinned MiniStack release and checks both version accessors agree with it.
/// </summary>
public sealed class MiniStackVersionPinningTest(PinnedMiniStackFixture fixture) : IClassFixture<PinnedMiniStackFixture>
{
    [Fact]
    public void GetMiniStackVersionReturnsThePinnedTag()
    {
        Assert.Equal(PinnedMiniStackFixture.Version, fixture.Container.GetMiniStackVersion());
    }

    [Fact]
    public async Task ServerReportsThePinnedVersion()
    {
        Assert.Equal(PinnedMiniStackFixture.Version, await fixture.Container.GetMiniStackVersionAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public void PinnedVersionSupportsCapabilityGating()
    {
        Assert.True(System.Version.Parse(fixture.Container.GetMiniStackVersion()) >= new Version(1, 3, 42));
    }
}
