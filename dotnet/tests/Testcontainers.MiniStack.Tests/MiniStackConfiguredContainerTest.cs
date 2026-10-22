using Amazon.SecurityToken;
using Amazon.SecurityToken.Model;
using Amazon.SQS;

namespace Testcontainers.MiniStack.Tests;

public sealed class ConfiguredMiniStackFixture() : MiniStackFixture(new MiniStackBuilder()
    .WithRegion(ConfiguredMiniStackFixture.Region)
    .WithCredentials(ConfiguredMiniStackFixture.AccountId, "configured-secret")
    .WithPersistence()
    .WithImdsV2Required())
{
    public const string Region = "eu-west-1";

    // MiniStack treats a 12-digit access key as the account ID.
    public const string AccountId = "123456789012";
}

/// <summary>
/// Checks that each builder option reaches a running MiniStack and changes its behaviour.
/// </summary>
public sealed class MiniStackConfiguredContainerTest(ConfiguredMiniStackFixture fixture) : IClassFixture<ConfiguredMiniStackFixture>
{
    private readonly MiniStackContainer _ministack = fixture.Container;

    [Fact]
    public void AccessorsReturnConfiguredValues()
    {
        Assert.Equal(ConfiguredMiniStackFixture.Region, _ministack.GetRegion());
        Assert.Equal(ConfiguredMiniStackFixture.AccountId, _ministack.GetAccessKey());
        Assert.Equal("configured-secret", _ministack.GetSecretKey());
    }

    [Fact]
    public async Task WithRegionAndCredentialsShapeGeneratedArns()
    {
        using var sqs = new AmazonSQSClient(fixture.Credentials, fixture.Config<AmazonSQSConfig>());
        var url = (await sqs.CreateQueueAsync("tc-region-" + MiniStackFixture.Uid(), TestContext.Current.CancellationToken)).QueueUrl;
        var arn = (await sqs.GetQueueAttributesAsync(url, ["QueueArn"], TestContext.Current.CancellationToken)).QueueARN;
        Assert.StartsWith($"arn:aws:sqs:{ConfiguredMiniStackFixture.Region}:{ConfiguredMiniStackFixture.AccountId}:", arn);
    }

    [Fact]
    public async Task WithCredentialsSetsTheCallerAccount()
    {
        using var sts = new AmazonSecurityTokenServiceClient(fixture.Credentials, fixture.Config<AmazonSecurityTokenServiceConfig>());
        var identity = await sts.GetCallerIdentityAsync(new GetCallerIdentityRequest(), TestContext.Current.CancellationToken);
        Assert.Equal(ConfiguredMiniStackFixture.AccountId, identity.Account);
    }

    [Theory]
    [InlineData("PERSIST_STATE", "1")]
    [InlineData("S3_PERSIST", "1")]
    [InlineData("MINISTACK_IMDS_V2_REQUIRED", "1")]
    [InlineData("MINISTACK_REGION", ConfiguredMiniStackFixture.Region)]
    public async Task EnvVarsReachTheContainer(string name, string expected)
    {
        var result = await _ministack.ExecAsync(["printenv", name], TestContext.Current.CancellationToken);
        Assert.Equal(expected, result.Stdout.Trim());
    }

    [Fact]
    public async Task WithImdsV2RequiredRejectsTokenlessMetadataCalls()
    {
        using var http = new HttpClient { BaseAddress = new Uri(_ministack.GetEndpoint()) };
        var ct = TestContext.Current.CancellationToken;

        using var tokenless = await http.GetAsync("/latest/meta-data/", ct);
        Assert.Equal(HttpStatusCode.Unauthorized, tokenless.StatusCode);

        using var tokenRequest = new HttpRequestMessage(HttpMethod.Put, "/latest/api/token");
        tokenRequest.Headers.Add("X-aws-ec2-metadata-token-ttl-seconds", "60");
        using var tokenResponse = await http.SendAsync(tokenRequest, ct);
        var token = await tokenResponse.Content.ReadAsStringAsync(ct);

        using var withToken = new HttpRequestMessage(HttpMethod.Get, "/latest/meta-data/");
        withToken.Headers.Add("X-aws-ec2-metadata-token", token);
        using var response = await http.SendAsync(withToken, ct);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }
}
