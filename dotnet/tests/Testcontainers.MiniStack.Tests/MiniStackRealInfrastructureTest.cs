using Amazon.ElastiCache;
using Amazon.ElastiCache.Model;
using Amazon.Lambda;
using Amazon.Lambda.Model;
using Amazon.RDS;
using Amazon.RDS.Model;
using Docker.DotNet.Models;
using Npgsql;
using StackExchange.Redis;

namespace Testcontainers.MiniStack.Tests;

// MINISTACK_RDS_PUBLIC_ENDPOINT makes RDS report localhost and the published port
// rather than the container's bridge IP, which only a Linux host can route to.
public sealed class RealInfrastructureMiniStackFixture() : MiniStackFixture(new MiniStackBuilder()
    .WithRealInfrastructure()
    .WithEnvironment("MINISTACK_RDS_PUBLIC_ENDPOINT", "1"));

/// <summary>
/// Real-infrastructure mode, modelled on the Java <c>MiniStackRealInfrastructureTest</c>:
/// MiniStack spawns sibling containers through the bind-mounted Docker socket.
/// </summary>
public sealed class MiniStackRealInfrastructureTest(RealInfrastructureMiniStackFixture fixture) : IClassFixture<RealInfrastructureMiniStackFixture>
{
    private readonly MiniStackContainer _ministack = fixture.Container;

    [Fact]
    public void ContainerIsRunning()
    {
        Assert.Equal(TestcontainersStates.Running, _ministack.State);
        Assert.True(_ministack.GetPort() > 0);
        Assert.False(string.IsNullOrEmpty(_ministack.InstanceId));
    }

    [Fact]
    public async Task LambdaCreateAndInvoke()
    {
        var ct = TestContext.Current.CancellationToken;
        using var lambda = new AmazonLambdaClient(fixture.Credentials, fixture.Config<AmazonLambdaConfig>());
        var name = "tc-lambda-invoke-" + MiniStackFixture.Uid();

        await lambda.CreateFunctionAsync(new CreateFunctionRequest
        {
            FunctionName = name,
            Runtime = Amazon.Lambda.Runtime.Python312,
            Handler = "handler.handler",
            Role = "arn:aws:iam::000000000000:role/test",
            Code = new FunctionCode { ZipFile = PythonZip("def handler(event, context):\n    return {'echo': event['name']}\n") },
        }, ct);

        await WaitUntilAsync(async () => (await lambda.GetFunctionConfigurationAsync(name, ct)).State == Amazon.Lambda.State.Active, TimeSpan.FromMinutes(2));

        var response = await lambda.InvokeAsync(new InvokeRequest
        {
            FunctionName = name,
            Payload = "{\"name\":\"ministack\"}",
        }, ct);

        Assert.Null(response.FunctionError);
        Assert.Equal("{\"echo\": \"ministack\"}", Encoding.UTF8.GetString(response.Payload.ToArray()));
    }

    [Fact]
    public async Task RdsCreateDbInstancePostgres()
    {
        var ct = TestContext.Current.CancellationToken;
        using var rds = new AmazonRDSClient(fixture.Credentials, fixture.Config<AmazonRDSConfig>());
        const string username = "admin";
        const string password = "password";
        const string dbName = "postgresdb";

        var response = await rds.CreateDBInstanceAsync(new CreateDBInstanceRequest
        {
            DBInstanceIdentifier = "tc-postgres-" + MiniStackFixture.Uid(),
            DBInstanceClass = "db.t3.micro",
            Engine = "postgres",
            MasterUsername = username,
            MasterUserPassword = password,
            DBName = dbName,
            AllocatedStorage = 20,
        }, ct);

        var endpoint = response.DBInstance.Endpoint;
        Assert.NotNull(endpoint);

        var connectionString = $"Host={endpoint.Address};Port={endpoint.Port};Database={dbName};Username={username};Password={password}";
        await WaitUntilAsync(async () =>
        {
            await using var connection = new NpgsqlConnection(connectionString);
            await connection.OpenAsync(ct);
            await using var command = new NpgsqlCommand("SELECT 1", connection);
            return Equals(1, await command.ExecuteScalarAsync(ct));
        }, TimeSpan.FromMinutes(2));
    }

    [Fact]
    public async Task ElastiCacheCreateCacheClusterRedis()
    {
        var ct = TestContext.Current.CancellationToken;
        using var elastiCache = new AmazonElastiCacheClient(fixture.Credentials, fixture.Config<AmazonElastiCacheConfig>());
        var clusterId = "tc-redis-" + MiniStackFixture.Uid();

        var response = await elastiCache.CreateCacheClusterAsync(new CreateCacheClusterRequest
        {
            CacheClusterId = clusterId,
            Engine = "redis",
            CacheNodeType = "cache.t3.micro",
            NumCacheNodes = 1,
        }, ct);

        Assert.Equal(clusterId, response.CacheCluster.CacheClusterId);
        Assert.Equal("redis", response.CacheCluster.Engine);

        var node = Assert.Single(response.CacheCluster.CacheNodes);
        var configuration = $"{node.Endpoint.Address}:{node.Endpoint.Port},abortConnect=false,connectTimeout=2000";
        await WaitUntilAsync(async () =>
        {
            await using var redis = await ConnectionMultiplexer.ConnectAsync(configuration);
            var db = redis.GetDatabase();
            await db.StringSetAsync("key", "value");
            return await db.StringGetAsync("key") == "value";
        }, TimeSpan.FromMinutes(2));
    }

    internal static MemoryStream PythonZip(string source)
    {
        var buffer = new MemoryStream();
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            using var writer = new StreamWriter(zip.CreateEntry("handler.py").Open());
            writer.Write(source);
        }

        buffer.Position = 0;
        return buffer;
    }

    internal static async Task WaitUntilAsync(Func<Task<bool>> condition, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        var errors = new List<Exception>();
        while (DateTime.UtcNow < deadline)
        {
            try
            {
                if (await condition())
                {
                    return;
                }
            }
            catch (Exception e)
            {
                errors.Add(e);
            }

            await Task.Delay(TimeSpan.FromSeconds(2));
        }

        // Report each distinct failure; the last one alone can hide an earlier, more telling one.
        var distinct = errors.GroupBy(e => e.GetType().Name + ": " + e.Message).Select(g => g.Last());
        throw new TimeoutException($"Condition not met within {timeout}", new AggregateException(distinct));
    }
}

/// <summary>
/// Disposing a real-infrastructure MiniStack removes the sibling containers it
/// spawned, and only those. Runs on its own so no other MiniStack is mid-test.
/// </summary>
[Collection(nameof(GlobalSettingsCollection))]
public sealed class MiniStackReaperTest
{
    [Fact]
    public async Task DisposeReapsOnlyThisInstancesNestedContainers()
    {
        var ct = TestContext.Current.CancellationToken;
        using var docker = TestcontainersSettings.OS.DockerEndpointAuthConfig.GetDockerClientBuilder(Guid.Empty).Build();

        // A stand-in for another MiniStack's nested container on the same daemon.
        var bystanderName = "tc-ministack-bystander-" + MiniStackFixture.Uid();
        var bystander = await docker.Containers.CreateContainerAsync(new CreateContainerParameters
        {
            Image = MiniStackBuilder.MiniStackImage,
            Name = bystanderName,
            Entrypoint = ["sleep", "300"],
            Labels = new Dictionary<string, string> { ["ministack"] = "rds", ["ministack.instance"] = "someone-else:4566" },
        }, ct);

        try
        {
            string instanceId;
            var ministack = new MiniStackBuilder().WithRealInfrastructure().Build();
            await using (ministack)
            {
                await ministack.StartAsync(ct);
                instanceId = ministack.InstanceId;
                Assert.False(string.IsNullOrEmpty(instanceId));

                using var elastiCache = new AmazonElastiCacheClient(
                    new BasicAWSCredentials(ministack.GetAccessKey(), ministack.GetSecretKey()),
                    new AmazonElastiCacheConfig { ServiceURL = ministack.GetEndpoint(), AuthenticationRegion = ministack.GetRegion() });
                await elastiCache.CreateCacheClusterAsync(new CreateCacheClusterRequest
                {
                    CacheClusterId = "tc-reap-" + MiniStackFixture.Uid(),
                    Engine = "redis",
                    CacheNodeType = "cache.t3.micro",
                    NumCacheNodes = 1,
                }, ct);

                Assert.NotEmpty(await ListByInstanceAsync(docker, instanceId));
            }

            Assert.Empty(await ListByInstanceAsync(docker, instanceId));
            var survivor = await docker.Containers.InspectContainerAsync(bystander.ID, ct);
            Assert.Equal("/" + bystanderName, survivor.Name);
        }
        finally
        {
            await docker.Containers.RemoveContainerAsync(bystander.ID, new ContainerRemoveParameters { Force = true }, ct);
        }
    }

    private static Task<IList<ContainerListResponse>> ListByInstanceAsync(Docker.DotNet.IDockerClient docker, string instanceId)
    {
        return docker.Containers.ListContainersAsync(new ContainersListParameters
        {
            All = true,
            Filters = new Dictionary<string, IDictionary<string, bool>> { ["label"] = new Dictionary<string, bool> { ["ministack.instance=" + instanceId] = true } },
        });
    }
}
