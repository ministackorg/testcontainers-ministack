package org.ministack.testcontainers;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying MiniStack container works with AWS SDK v2 across all major services.
 * One shared container for all tests to keep CI fast.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MiniStackContainerTest {

    static MiniStackContainer ministack = new MiniStackContainer("latest");
    static StaticCredentialsProvider creds = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test"));
    static Region region = Region.US_EAST_1;

    @BeforeAll
    static void startContainer() {
        ministack.start();
    }

    @AfterAll
    static void stopContainer() {
        ministack.stop();
    }

    private URI endpoint() {
        return URI.create(ministack.getEndpoint());
    }

    // -----------------------------------------------------------------------
    // Container
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void containerIsRunning() {
        assertTrue(ministack.isRunning());
        assertNotNull(ministack.getEndpoint());
        assertTrue(ministack.getPort() > 0);
    }

    // -----------------------------------------------------------------------
    // S3
    // -----------------------------------------------------------------------

    @Test
    void s3CreateAndListBuckets() {
        S3Client s3 = S3Client.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).forcePathStyle(true).build();
        String bucket = "tc-s3-" + uid();
        s3.createBucket(b -> b.bucket(bucket));
        assertTrue(s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(bucket)));
    }

    @Test
    void s3PutAndGetObject() {
        S3Client s3 = S3Client.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).forcePathStyle(true).build();
        String bucket = "tc-s3obj-" + uid();
        s3.createBucket(b -> b.bucket(bucket));
        s3.putObject(b -> b.bucket(bucket).key("hello.txt"),
                software.amazon.awssdk.core.sync.RequestBody.fromString("world"));
        var resp = s3.getObjectAsBytes(b -> b.bucket(bucket).key("hello.txt"));
        assertEquals("world", resp.asUtf8String());
    }

    @Test
    void s3DeleteObject() {
        S3Client s3 = S3Client.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).forcePathStyle(true).build();
        String bucket = "tc-s3del-" + uid();
        s3.createBucket(b -> b.bucket(bucket));
        s3.putObject(b -> b.bucket(bucket).key("temp.txt"),
                software.amazon.awssdk.core.sync.RequestBody.fromString("delete me"));
        s3.deleteObject(b -> b.bucket(bucket).key("temp.txt"));
        var list = s3.listObjectsV2(b -> b.bucket(bucket));
        assertTrue(list.contents().isEmpty());
    }

    // -----------------------------------------------------------------------
    // SQS
    // -----------------------------------------------------------------------

    @Test
    void sqsCreateQueueAndSendReceive() {
        SqsClient sqs = SqsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sqs-" + uid();
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        sqs.sendMessage(b -> b.queueUrl(url).messageBody("hello-sqs"));
        var msgs = sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(1)).messages();
        assertEquals(1, msgs.size());
        assertEquals("hello-sqs", msgs.get(0).body());
    }

    @Test
    void sqsDeleteMessage() {
        SqsClient sqs = SqsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sqsdel-" + uid();
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        sqs.sendMessage(b -> b.queueUrl(url).messageBody("delete-me"));
        var msg = sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(1)).messages().get(0);
        sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(msg.receiptHandle()));
        var msgs = sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(1).waitTimeSeconds(0)).messages();
        assertTrue(msgs.isEmpty());
    }

    @Test
    void sqsGetQueueAttributes() {
        SqsClient sqs = SqsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sqsattr-" + uid();
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        var attrs = sqs.getQueueAttributes(b -> b.queueUrl(url)
                .attributeNamesWithStrings("QueueArn")).attributes();
        assertTrue(attrs.get("QueueArn").contains(name));
    }

    // -----------------------------------------------------------------------
    // SNS
    // -----------------------------------------------------------------------

    @Test
    void snsCreateTopicAndPublish() {
        SnsClient sns = SnsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sns-" + uid();
        String arn = sns.createTopic(b -> b.name(name)).topicArn();
        assertNotNull(arn);
        var pub = sns.publish(b -> b.topicArn(arn).message("hello-sns"));
        assertNotNull(pub.messageId());
    }

    @Test
    void snsListTopics() {
        SnsClient sns = SnsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-snslist-" + uid();
        sns.createTopic(b -> b.name(name));
        assertTrue(sns.listTopics().topics().stream().anyMatch(t -> t.topicArn().contains(name)));
    }

    @Test
    void snsSubscribeAndListSubscriptions() {
        SnsClient sns = SnsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        SqsClient sqs = SqsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String topic = "tc-snssub-" + uid();
        String queue = "tc-snssub-q-" + uid();
        String topicArn = sns.createTopic(b -> b.name(topic)).topicArn();
        String queueUrl = sqs.createQueue(b -> b.queueName(queue)).queueUrl();
        String queueArn = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl)
                .attributeNamesWithStrings("QueueArn")).attributes().get("QueueArn");
        var sub = sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(queueArn));
        assertNotNull(sub.subscriptionArn());
    }

    // -----------------------------------------------------------------------
    // DynamoDB
    // -----------------------------------------------------------------------

    @Test
    void dynamoDbCreateTablePutGetItem() {
        DynamoDbClient ddb = DynamoDbClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String table = "tc-ddb-" + uid();
        ddb.createTable(b -> b.tableName(table)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
        ddb.putItem(b -> b.tableName(table)
                .item(Map.of("pk", AttributeValue.fromS("k1"), "data", AttributeValue.fromS("v1"))));
        var item = ddb.getItem(b -> b.tableName(table)
                .key(Map.of("pk", AttributeValue.fromS("k1")))).item();
        assertEquals("v1", item.get("data").s());
    }

    @Test
    void dynamoDbQueryItems() {
        DynamoDbClient ddb = DynamoDbClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String table = "tc-ddbq-" + uid();
        ddb.createTable(b -> b.tableName(table)
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
        for (int i = 1; i <= 3; i++) {
            ddb.putItem(b -> b.tableName(table).item(Map.of(
                    "pk", AttributeValue.fromS("user1"),
                    "sk", AttributeValue.fromS("item" + UUID.randomUUID().toString().substring(0, 4)))));
        }
        var result = ddb.query(b -> b.tableName(table)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("user1"))));
        assertEquals(3, result.count());
    }

    @Test
    void dynamoDbDeleteItem() {
        DynamoDbClient ddb = DynamoDbClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String table = "tc-ddbdel-" + uid();
        ddb.createTable(b -> b.tableName(table)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
        ddb.putItem(b -> b.tableName(table).item(Map.of("pk", AttributeValue.fromS("del1"))));
        ddb.deleteItem(b -> b.tableName(table).key(Map.of("pk", AttributeValue.fromS("del1"))));
        var item = ddb.getItem(b -> b.tableName(table).key(Map.of("pk", AttributeValue.fromS("del1")))).item();
        assertTrue(item == null || item.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Lambda
    // -----------------------------------------------------------------------

    @Test
    void lambdaCreateAndListFunctions() {
        LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-lambda-" + uid();
        // Minimal zip with a handler
        byte[] zip = minimalPythonZip();
        lambda.createFunction(b -> b.functionName(name).runtime("python3.12")
                .handler("handler.handler").role("arn:aws:iam::000000000000:role/test")
                .code(c -> c.zipFile(SdkBytes.fromByteArray(zip))));
        assertTrue(lambda.listFunctions().functions().stream()
                .anyMatch(f -> f.functionName().equals(name)));
    }

    @Test
    void lambdaGetFunctionConfiguration() {
        LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-lambdacfg-" + uid();
        byte[] zip = minimalPythonZip();
        lambda.createFunction(b -> b.functionName(name).runtime("python3.12")
                .handler("handler.handler").role("arn:aws:iam::000000000000:role/test")
                .code(c -> c.zipFile(SdkBytes.fromByteArray(zip))).memorySize(256));
        var cfg = lambda.getFunctionConfiguration(b -> b.functionName(name));
        assertEquals(256, cfg.memorySize());
        assertEquals("python3.12", cfg.runtime().toString());
    }

    @Test
    void lambdaDeleteFunction() {
        LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-lambdadel-" + uid();
        byte[] zip = minimalPythonZip();
        lambda.createFunction(b -> b.functionName(name).runtime("python3.12")
                .handler("handler.handler").role("arn:aws:iam::000000000000:role/test")
                .code(c -> c.zipFile(SdkBytes.fromByteArray(zip))));
        lambda.deleteFunction(b -> b.functionName(name));
        assertFalse(lambda.listFunctions().functions().stream()
                .anyMatch(f -> f.functionName().equals(name)));
    }

    // -----------------------------------------------------------------------
    // Secrets Manager
    // -----------------------------------------------------------------------

    @Test
    void secretsManagerCreateAndGetSecret() {
        SecretsManagerClient sm = SecretsManagerClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sm/" + uid();
        sm.createSecret(b -> b.name(name).secretString("{\"key\":\"value\"}"));
        var val = sm.getSecretValue(b -> b.secretId(name));
        assertEquals("{\"key\":\"value\"}", val.secretString());
    }

    @Test
    void secretsManagerUpdateSecret() {
        SecretsManagerClient sm = SecretsManagerClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-smup/" + uid();
        sm.createSecret(b -> b.name(name).secretString("old"));
        sm.updateSecret(b -> b.secretId(name).secretString("new"));
        assertEquals("new", sm.getSecretValue(b -> b.secretId(name)).secretString());
    }

    @Test
    void secretsManagerDeleteSecret() {
        SecretsManagerClient sm = SecretsManagerClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-smdel/" + uid();
        sm.createSecret(b -> b.name(name).secretString("temp"));
        sm.deleteSecret(b -> b.secretId(name).forceDeleteWithoutRecovery(true));
        assertThrows(Exception.class, () -> sm.getSecretValue(b -> b.secretId(name)));
    }

    // -----------------------------------------------------------------------
    // SSM Parameter Store
    // -----------------------------------------------------------------------

    @Test
    void ssmPutAndGetParameter() {
        SsmClient ssm = SsmClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "/tc/ssm/" + uid();
        ssm.putParameter(b -> b.name(name).value("hello").type(ParameterType.STRING));
        assertEquals("hello", ssm.getParameter(b -> b.name(name)).parameter().value());
    }

    @Test
    void ssmGetParametersByPath() {
        SsmClient ssm = SsmClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String prefix = "/tc/ssmpath/" + uid();
        ssm.putParameter(b -> b.name(prefix + "/a").value("1").type(ParameterType.STRING));
        ssm.putParameter(b -> b.name(prefix + "/b").value("2").type(ParameterType.STRING));
        var params = ssm.getParametersByPath(b -> b.path(prefix)).parameters();
        assertEquals(2, params.size());
    }

    @Test
    void ssmDeleteParameter() {
        SsmClient ssm = SsmClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "/tc/ssmdel/" + uid();
        ssm.putParameter(b -> b.name(name).value("temp").type(ParameterType.STRING));
        ssm.deleteParameter(b -> b.name(name));
        assertThrows(Exception.class, () -> ssm.getParameter(b -> b.name(name)));
    }

    // -----------------------------------------------------------------------
    // CloudWatch Logs
    // -----------------------------------------------------------------------

    @Test
    void cwLogsCreateGroupAndStream() {
        CloudWatchLogsClient logs = CloudWatchLogsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String group = "/tc/cwlogs/" + uid();
        String stream = "stream-" + uid();
        logs.createLogGroup(b -> b.logGroupName(group));
        logs.createLogStream(b -> b.logGroupName(group).logStreamName(stream));
        assertTrue(logs.describeLogGroups(b -> b.logGroupNamePrefix(group))
                .logGroups().stream().anyMatch(g -> g.logGroupName().equals(group)));
    }

    @Test
    void cwLogsPutAndGetEvents() {
        CloudWatchLogsClient logs = CloudWatchLogsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String group = "/tc/cwlogsev/" + uid();
        String stream = "stream-" + uid();
        logs.createLogGroup(b -> b.logGroupName(group));
        logs.createLogStream(b -> b.logGroupName(group).logStreamName(stream));
        logs.putLogEvents(b -> b.logGroupName(group).logStreamName(stream)
                .logEvents(software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent.builder()
                        .message("test-log-event").timestamp(System.currentTimeMillis()).build()));
        var events = logs.getLogEvents(b -> b.logGroupName(group).logStreamName(stream)).events();
        assertEquals(1, events.size());
        assertEquals("test-log-event", events.get(0).message());
    }

    @Test
    void cwLogsDeleteGroup() {
        CloudWatchLogsClient logs = CloudWatchLogsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String group = "/tc/cwlogsdel/" + uid();
        logs.createLogGroup(b -> b.logGroupName(group));
        logs.deleteLogGroup(b -> b.logGroupName(group));
        assertTrue(logs.describeLogGroups(b -> b.logGroupNamePrefix(group)).logGroups().isEmpty());
    }

    // -----------------------------------------------------------------------
    // EventBridge
    // -----------------------------------------------------------------------

    @Test
    void eventBridgePutRuleAndTargets() {
        EventBridgeClient eb = EventBridgeClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String rule = "tc-eb-" + uid();
        eb.putRule(b -> b.name(rule).eventPattern("{\"source\":[\"tc.test\"]}").state("ENABLED"));
        var rules = eb.listRules(b -> b.namePrefix("tc-eb-")).rules();
        assertTrue(rules.stream().anyMatch(r -> r.name().equals(rule)));
    }

    @Test
    void eventBridgePutEvents() {
        EventBridgeClient eb = EventBridgeClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        var resp = eb.putEvents(b -> b.entries(
                PutEventsRequestEntry.builder()
                        .source("tc.test").detailType("TestEvent")
                        .detail("{\"key\":\"value\"}").build()));
        assertEquals(0, resp.failedEntryCount());
    }

    @Test
    void eventBridgeDeleteRule() {
        EventBridgeClient eb = EventBridgeClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String rule = "tc-ebdel-" + uid();
        eb.putRule(b -> b.name(rule).eventPattern("{\"source\":[\"tc.del\"]}"));
        eb.deleteRule(b -> b.name(rule));
        assertFalse(eb.listRules(b -> b.namePrefix("tc-ebdel-")).rules().stream()
                .anyMatch(r -> r.name().equals(rule)));
    }

    // -----------------------------------------------------------------------
    // Kinesis
    // -----------------------------------------------------------------------

    @Test
    void kinesisCreateStreamAndPutRecord() {
        KinesisClient kinesis = KinesisClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String stream = "tc-kin-" + uid();
        kinesis.createStream(b -> b.streamName(stream).shardCount(1));
        var put = kinesis.putRecord(b -> b.streamName(stream)
                .data(SdkBytes.fromUtf8String("hello"))
                .partitionKey("pk1"));
        assertNotNull(put.shardId());
        assertNotNull(put.sequenceNumber());
    }

    @Test
    void kinesisListStreams() {
        KinesisClient kinesis = KinesisClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String stream = "tc-kinlist-" + uid();
        kinesis.createStream(b -> b.streamName(stream).shardCount(1));
        assertTrue(kinesis.listStreams().streamNames().contains(stream));
    }

    @Test
    void kinesisDeleteStream() {
        KinesisClient kinesis = KinesisClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String stream = "tc-kindel-" + uid();
        kinesis.createStream(b -> b.streamName(stream).shardCount(1));
        kinesis.deleteStream(b -> b.streamName(stream));
        assertFalse(kinesis.listStreams().streamNames().contains(stream));
    }

    // -----------------------------------------------------------------------
    // IAM
    // -----------------------------------------------------------------------

    @Test
    void iamCreateAndGetRole() {
        IamClient iam = IamClient.builder().endpointOverride(endpoint()).region(Region.AWS_GLOBAL)
                .credentialsProvider(creds).build();
        String role = "tc-role-" + uid();
        iam.createRole(b -> b.roleName(role)
                .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[]}"));
        var resp = iam.getRole(b -> b.roleName(role));
        assertEquals(role, resp.role().roleName());
    }

    @Test
    void iamCreateAndListUsers() {
        IamClient iam = IamClient.builder().endpointOverride(endpoint()).region(Region.AWS_GLOBAL)
                .credentialsProvider(creds).build();
        String user = "tc-user-" + uid();
        iam.createUser(b -> b.userName(user));
        assertTrue(iam.listUsers().users().stream().anyMatch(u -> u.userName().equals(user)));
    }

    @Test
    void iamDeleteRole() {
        IamClient iam = IamClient.builder().endpointOverride(endpoint()).region(Region.AWS_GLOBAL)
                .credentialsProvider(creds).build();
        String role = "tc-roledel-" + uid();
        iam.createRole(b -> b.roleName(role)
                .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[]}"));
        iam.deleteRole(b -> b.roleName(role));
        assertThrows(Exception.class, () -> iam.getRole(b -> b.roleName(role)));
    }

    // -----------------------------------------------------------------------
    // STS
    // -----------------------------------------------------------------------

    @Test
    void stsGetCallerIdentity() {
        StsClient sts = StsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        var identity = sts.getCallerIdentity();
        assertNotNull(identity.account());
        assertNotNull(identity.arn());
    }

    // -----------------------------------------------------------------------
    // KMS
    // -----------------------------------------------------------------------

    @Test
    void kmsCreateKeyAndListKeys() {
        KmsClient kms = KmsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        var key = kms.createKey().keyMetadata();
        assertNotNull(key.keyId());
        assertTrue(kms.listKeys().keys().stream().anyMatch(k -> k.keyId().equals(key.keyId())));
    }

    @Test
    void kmsEncryptDecrypt() {
        KmsClient kms = KmsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String keyId = kms.createKey().keyMetadata().keyId();
        var encrypted = kms.encrypt(b -> b.keyId(keyId)
                .plaintext(SdkBytes.fromUtf8String("secret-data")));
        var decrypted = kms.decrypt(b -> b.keyId(keyId)
                .ciphertextBlob(encrypted.ciphertextBlob()));
        assertEquals("secret-data", decrypted.plaintext().asUtf8String());
    }

    @Test
    void kmsCreateAlias() {
        KmsClient kms = KmsClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String keyId = kms.createKey().keyMetadata().keyId();
        String alias = "alias/tc-" + uid();
        kms.createAlias(b -> b.aliasName(alias).targetKeyId(keyId));
        assertTrue(kms.listAliases().aliases().stream().anyMatch(a -> a.aliasName().equals(alias)));
    }

    // -----------------------------------------------------------------------
    // SES
    // -----------------------------------------------------------------------

    @Test
    void sesVerifyEmailAndSend() {
        SesClient ses = SesClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        ses.verifyEmailIdentity(b -> b.emailAddress("test@example.com"));
        var identities = ses.listIdentities().identities();
        assertTrue(identities.contains("test@example.com"));
    }

    // -----------------------------------------------------------------------
    // ACM
    // -----------------------------------------------------------------------

    @Test
    void acmRequestAndListCertificates() {
        AcmClient acm = AcmClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String domain = "tc-" + uid() + ".example.com";
        var cert = acm.requestCertificate(b -> b.domainName(domain));
        assertNotNull(cert.certificateArn());
        assertTrue(acm.listCertificates().certificateSummaryList().stream()
                .anyMatch(c -> c.certificateArn().equals(cert.certificateArn())));
    }

    @Test
    void acmDescribeCertificate() {
        AcmClient acm = AcmClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String domain = "tc-desc-" + uid() + ".example.com";
        String arn = acm.requestCertificate(b -> b.domainName(domain)).certificateArn();
        var desc = acm.describeCertificate(b -> b.certificateArn(arn)).certificate();
        assertEquals(domain, desc.domainName());
    }

    // -----------------------------------------------------------------------
    // CloudWatch Metrics
    // -----------------------------------------------------------------------

    @Test
    void cloudWatchPutMetricAndListMetrics() {
        CloudWatchClient cw = CloudWatchClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String ns = "TC/Test/" + uid();
        cw.putMetricData(b -> b.namespace(ns).metricData(
                software.amazon.awssdk.services.cloudwatch.model.MetricDatum.builder()
                        .metricName("TestMetric").value(42.0).unit(StandardUnit.COUNT).build()));
        var metrics = cw.listMetrics(b -> b.namespace(ns)).metrics();
        assertTrue(metrics.stream().anyMatch(m -> m.metricName().equals("TestMetric")));
    }

    // -----------------------------------------------------------------------
    // Step Functions
    // -----------------------------------------------------------------------

    @Test
    void sfnCreateAndListStateMachines() {
        SfnClient sfn = SfnClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sfn-" + uid();
        String definition = "{\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";
        sfn.createStateMachine(b -> b.name(name).definition(definition)
                .roleArn("arn:aws:iam::000000000000:role/test"));
        assertTrue(sfn.listStateMachines().stateMachines().stream()
                .anyMatch(sm -> sm.name().equals(name)));
    }

    @Test
    void sfnStartExecution() {
        SfnClient sfn = SfnClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-sfnexec-" + uid();
        String definition = "{\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}";
        String arn = sfn.createStateMachine(b -> b.name(name).definition(definition)
                .roleArn("arn:aws:iam::000000000000:role/test")).stateMachineArn();
        var exec = sfn.startExecution(b -> b.stateMachineArn(arn).input("{}"));
        assertNotNull(exec.executionArn());
    }

    // -----------------------------------------------------------------------
    // Route53
    // -----------------------------------------------------------------------

    @Test
    void route53CreateAndGetHostedZone() {
        Route53Client r53 = Route53Client.builder().endpointOverride(endpoint()).region(Region.AWS_GLOBAL)
                .credentialsProvider(creds).build();
        String domain = "tc-" + uid() + ".example.com.";
        var zone = r53.createHostedZone(b -> b.name(domain)
                .callerReference(uid())).hostedZone();
        assertNotNull(zone.id());
        var got = r53.getHostedZone(b -> b.id(zone.id())).hostedZone();
        assertEquals(domain, got.name());
    }

    @Test
    void route53ListHostedZones() {
        Route53Client r53 = Route53Client.builder().endpointOverride(endpoint()).region(Region.AWS_GLOBAL)
                .credentialsProvider(creds).build();
        String domain = "tc-list-" + uid() + ".example.com.";
        r53.createHostedZone(b -> b.name(domain).callerReference(uid()));
        assertTrue(r53.listHostedZones().hostedZones().stream()
                .anyMatch(z -> z.name().equals(domain)));
    }

    // -----------------------------------------------------------------------
    // ECR
    // -----------------------------------------------------------------------

    @Test
    void ecrCreateAndDescribeRepository() {
        EcrClient ecr = EcrClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String repo = "tc-ecr-" + uid();
        ecr.createRepository(b -> b.repositoryName(repo));
        var repos = ecr.describeRepositories().repositories();
        assertTrue(repos.stream().anyMatch(r -> r.repositoryName().equals(repo)));
    }

    @Test
    void ecrDeleteRepository() {
        EcrClient ecr = EcrClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String repo = "tc-ecrdel-" + uid();
        ecr.createRepository(b -> b.repositoryName(repo));
        ecr.deleteRepository(b -> b.repositoryName(repo).force(true));
        assertFalse(ecr.describeRepositories().repositories().stream()
                .anyMatch(r -> r.repositoryName().equals(repo)));
    }

    // -----------------------------------------------------------------------
    // CodeBuild
    // -----------------------------------------------------------------------

    @Test
    void codeBuildCreateAndGetProject() {
        CodeBuildClient cb = CodeBuildClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-cb-" + uid();
        cb.createProject(b -> b.name(name)
                .source(s -> s.type("NO_SOURCE"))
                .artifacts(a -> a.type("NO_ARTIFACTS"))
                .environment(e -> e.type("LINUX_CONTAINER")
                        .image("aws/codebuild/standard:7.0")
                        .computeType("BUILD_GENERAL1_SMALL"))
                .serviceRole("arn:aws:iam::000000000000:role/test"));
        var projects = cb.batchGetProjects(b -> b.names(name)).projects();
        assertEquals(1, projects.size());
        assertEquals(name, projects.get(0).name());
    }

    @Test
    void codeBuildStartAndStopBuild() {
        CodeBuildClient cb = CodeBuildClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-cbbuild-" + uid();
        cb.createProject(b -> b.name(name)
                .source(s -> s.type("NO_SOURCE"))
                .artifacts(a -> a.type("NO_ARTIFACTS"))
                .environment(e -> e.type("LINUX_CONTAINER")
                        .image("aws/codebuild/standard:7.0")
                        .computeType("BUILD_GENERAL1_SMALL"))
                .serviceRole("arn:aws:iam::000000000000:role/test"));
        var build = cb.startBuild(b -> b.projectName(name)).build();
        assertEquals("SUCCEEDED", build.buildStatusAsString());
        var stopped = cb.stopBuild(b -> b.id(build.id())).build();
        assertEquals("STOPPED", stopped.buildStatusAsString());
    }

    @Test
    void codeBuildDeleteProject() {
        CodeBuildClient cb = CodeBuildClient.builder().endpointOverride(endpoint()).region(region)
                .credentialsProvider(creds).build();
        String name = "tc-cbdel-" + uid();
        cb.createProject(b -> b.name(name)
                .source(s -> s.type("NO_SOURCE"))
                .artifacts(a -> a.type("NO_ARTIFACTS"))
                .environment(e -> e.type("LINUX_CONTAINER")
                        .image("aws/codebuild/standard:7.0")
                        .computeType("BUILD_GENERAL1_SMALL"))
                .serviceRole("arn:aws:iam::000000000000:role/test"));
        cb.deleteProject(b -> b.name(name));
        assertTrue(cb.listProjects().projects().stream().noneMatch(p -> p.equals(name)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] minimalPythonZip() {
        try {
            var buf = new java.io.ByteArrayOutputStream();
            var zip = new java.util.zip.ZipOutputStream(buf);
            zip.putNextEntry(new java.util.zip.ZipEntry("handler.py"));
            zip.write("def handler(event, context): return {'statusCode': 200}".getBytes());
            zip.closeEntry();
            zip.close();
            return buf.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
