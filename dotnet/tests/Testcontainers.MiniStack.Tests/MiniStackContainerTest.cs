using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.S3;
using Amazon.S3.Model;
using Amazon.SQS;
using Amazon.SQS.Model;

namespace Testcontainers.MiniStack.Tests;

/// <summary>
/// Round trips against a real MiniStack container with the AWS SDK for .NET,
/// modelled on the Java <c>MiniStackContainerTest</c>.
/// </summary>
public sealed class MiniStackContainerTest(DefaultMiniStackFixture fixture) : IClassFixture<DefaultMiniStackFixture>
{
    private readonly MiniStackContainer _ministack = fixture.Container;

    private static string Uid() => MiniStackFixture.Uid();

    // -----------------------------------------------------------------------
    // Container
    // -----------------------------------------------------------------------

    [Fact]
    public void ContainerIsRunning()
    {
        Assert.Equal(TestcontainersStates.Running, _ministack.State);
        Assert.StartsWith("http://", _ministack.GetEndpoint());
        Assert.EndsWith(":" + _ministack.GetPort(), _ministack.GetEndpoint());
        Assert.True(_ministack.GetPort() > 0);
        Assert.Equal("test", _ministack.GetAccessKey());
        Assert.Equal("test", _ministack.GetSecretKey());
        Assert.Equal("us-east-1", _ministack.GetRegion());
    }

    [Fact]
    public async Task HealthEndpointReturnsOk()
    {
        using var http = new HttpClient();
        using var response = await http.GetAsync(_ministack.GetEndpoint() + MiniStackBuilder.HealthPath, TestContext.Current.CancellationToken);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task VersionAccessorsReportTagAndServerVersion()
    {
        Assert.Equal("latest", _ministack.GetMiniStackVersion());
        var serverVersion = await _ministack.GetMiniStackVersionAsync(TestContext.Current.CancellationToken);
        Assert.True(Version.TryParse(serverVersion, out _), $"'{serverVersion}' is not a version");
    }

    // -----------------------------------------------------------------------
    // S3
    // -----------------------------------------------------------------------

    private AmazonS3Client S3()
    {
        var config = fixture.Config<AmazonS3Config>();
        config.ForcePathStyle = true;
        return new AmazonS3Client(fixture.Credentials, config);
    }

    [Fact]
    public async Task S3CreateAndListBuckets()
    {
        using var s3 = S3();
        var bucket = "tc-s3-" + Uid();
        await s3.PutBucketAsync(bucket, TestContext.Current.CancellationToken);
        var buckets = await s3.ListBucketsAsync(TestContext.Current.CancellationToken);
        Assert.Contains(buckets.Buckets, b => b.BucketName == bucket);
    }

    [Fact]
    public async Task S3PutAndGetObject()
    {
        using var s3 = S3();
        var bucket = "tc-s3obj-" + Uid();
        await s3.PutBucketAsync(bucket, TestContext.Current.CancellationToken);
        await s3.PutObjectAsync(new PutObjectRequest { BucketName = bucket, Key = "hello.txt", ContentBody = "world" }, TestContext.Current.CancellationToken);
        using var response = await s3.GetObjectAsync(bucket, "hello.txt", TestContext.Current.CancellationToken);
        using var reader = new StreamReader(response.ResponseStream);
        Assert.Equal("world", await reader.ReadToEndAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task S3DeleteObject()
    {
        using var s3 = S3();
        var bucket = "tc-s3del-" + Uid();
        await s3.PutBucketAsync(bucket, TestContext.Current.CancellationToken);
        await s3.PutObjectAsync(new PutObjectRequest { BucketName = bucket, Key = "temp.txt", ContentBody = "delete me" }, TestContext.Current.CancellationToken);
        await s3.DeleteObjectAsync(bucket, "temp.txt", TestContext.Current.CancellationToken);
        var list = await s3.ListObjectsV2Async(new ListObjectsV2Request { BucketName = bucket }, TestContext.Current.CancellationToken);
        Assert.True(list.S3Objects == null || list.S3Objects.Count == 0);
    }

    // -----------------------------------------------------------------------
    // SQS
    // -----------------------------------------------------------------------

    private AmazonSQSClient Sqs() => new(fixture.Credentials, fixture.Config<AmazonSQSConfig>());

    [Fact]
    public async Task SqsCreateQueueAndSendReceive()
    {
        using var sqs = Sqs();
        var url = (await sqs.CreateQueueAsync("tc-sqs-" + Uid(), TestContext.Current.CancellationToken)).QueueUrl;
        await sqs.SendMessageAsync(url, "hello-sqs", TestContext.Current.CancellationToken);
        var messages = (await sqs.ReceiveMessageAsync(new ReceiveMessageRequest { QueueUrl = url, MaxNumberOfMessages = 1 }, TestContext.Current.CancellationToken)).Messages;
        var message = Assert.Single(messages);
        Assert.Equal("hello-sqs", message.Body);
    }

    [Fact]
    public async Task SqsDeleteMessage()
    {
        using var sqs = Sqs();
        var url = (await sqs.CreateQueueAsync("tc-sqsdel-" + Uid(), TestContext.Current.CancellationToken)).QueueUrl;
        await sqs.SendMessageAsync(url, "delete-me", TestContext.Current.CancellationToken);
        var message = (await sqs.ReceiveMessageAsync(new ReceiveMessageRequest { QueueUrl = url, MaxNumberOfMessages = 1 }, TestContext.Current.CancellationToken)).Messages.Single();
        await sqs.DeleteMessageAsync(url, message.ReceiptHandle, TestContext.Current.CancellationToken);
        var messages = (await sqs.ReceiveMessageAsync(new ReceiveMessageRequest { QueueUrl = url, MaxNumberOfMessages = 1, WaitTimeSeconds = 0 }, TestContext.Current.CancellationToken)).Messages;
        Assert.True(messages == null || messages.Count == 0);
    }

    [Fact]
    public async Task SqsGetQueueAttributes()
    {
        using var sqs = Sqs();
        var name = "tc-sqsattr-" + Uid();
        var url = (await sqs.CreateQueueAsync(name, TestContext.Current.CancellationToken)).QueueUrl;
        var attributes = await sqs.GetQueueAttributesAsync(url, ["QueueArn"], TestContext.Current.CancellationToken);
        Assert.Contains(name, attributes.QueueARN);
    }

    // -----------------------------------------------------------------------
    // DynamoDB
    // -----------------------------------------------------------------------

    private AmazonDynamoDBClient DynamoDb() => new(fixture.Credentials, fixture.Config<AmazonDynamoDBConfig>());

    private static CreateTableRequest Table(string name, bool withSortKey = false)
    {
        var request = new CreateTableRequest
        {
            TableName = name,
            BillingMode = BillingMode.PAY_PER_REQUEST,
            KeySchema = [new KeySchemaElement("pk", KeyType.HASH)],
            AttributeDefinitions = [new AttributeDefinition("pk", ScalarAttributeType.S)],
        };

        if (withSortKey)
        {
            request.KeySchema.Add(new KeySchemaElement("sk", KeyType.RANGE));
            request.AttributeDefinitions.Add(new AttributeDefinition("sk", ScalarAttributeType.S));
        }

        return request;
    }

    [Fact]
    public async Task DynamoDbCreateTablePutGetItem()
    {
        using var ddb = DynamoDb();
        var table = "tc-ddb-" + Uid();
        await ddb.CreateTableAsync(Table(table), TestContext.Current.CancellationToken);
        await ddb.PutItemAsync(table, new Dictionary<string, AttributeValue> { ["pk"] = new("k1"), ["data"] = new("v1") }, TestContext.Current.CancellationToken);
        var item = (await ddb.GetItemAsync(table, new Dictionary<string, AttributeValue> { ["pk"] = new("k1") }, TestContext.Current.CancellationToken)).Item;
        Assert.Equal("v1", item["data"].S);
    }

    [Fact]
    public async Task DynamoDbQueryItems()
    {
        using var ddb = DynamoDb();
        var table = "tc-ddbq-" + Uid();
        await ddb.CreateTableAsync(Table(table, withSortKey: true), TestContext.Current.CancellationToken);
        for (var i = 0; i < 3; i++)
        {
            await ddb.PutItemAsync(table, new Dictionary<string, AttributeValue> { ["pk"] = new("user1"), ["sk"] = new("item" + Uid()) }, TestContext.Current.CancellationToken);
        }

        var result = await ddb.QueryAsync(new QueryRequest
        {
            TableName = table,
            KeyConditionExpression = "pk = :pk",
            ExpressionAttributeValues = new Dictionary<string, AttributeValue> { [":pk"] = new("user1") },
        }, TestContext.Current.CancellationToken);
        Assert.Equal(3, result.Count);
    }

    [Fact]
    public async Task DynamoDbDeleteItem()
    {
        using var ddb = DynamoDb();
        var table = "tc-ddbdel-" + Uid();
        await ddb.CreateTableAsync(Table(table), TestContext.Current.CancellationToken);
        var key = new Dictionary<string, AttributeValue> { ["pk"] = new("del1") };
        await ddb.PutItemAsync(table, key, TestContext.Current.CancellationToken);
        await ddb.DeleteItemAsync(table, key, TestContext.Current.CancellationToken);
        var item = (await ddb.GetItemAsync(table, key, TestContext.Current.CancellationToken)).Item;
        Assert.True(item == null || item.Count == 0);
    }
}
