# Testcontainers.MiniStack

[Testcontainers](https://dotnet.testcontainers.org/) module for [MiniStack](https://ministack.org), a free, open-source AWS emulator that serves 55+ AWS services on a single port.

```csharp
await using var ministack = new MiniStackBuilder().Build();
await ministack.StartAsync();

var s3 = new AmazonS3Client(
    new BasicAWSCredentials(ministack.GetAccessKey(), ministack.GetSecretKey()),
    new AmazonS3Config { ServiceURL = ministack.GetEndpoint(), AuthenticationRegion = ministack.GetRegion(), ForcePathStyle = true });
```

Builder options: `WithRegion`, `WithCredentials`, `WithPersistence`, `WithImdsV2Required` and `WithRealInfrastructure`.

See the [repository README](https://github.com/ministackorg/testcontainers-ministack#net) for the full guide.
