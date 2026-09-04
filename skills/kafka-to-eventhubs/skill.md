# Kafka to Azure Event Hubs Migration

## Goal

Standardize the migration of self-managed Apache Kafka workloads to Azure Event
Hubs so that every application in the portfolio lands on the same authentication,
SDK, partitioning, and schema-compatibility patterns.

## When to apply this skill

Apply this skill whenever the agent encounters:

- `org.apache.kafka:kafka-clients`, `spring-kafka`, or `Confluent.Kafka`
  dependencies.
- Broker configuration such as `bootstrap.servers`, `sasl.jaas.config`,
  `security.protocol`, or `schema.registry.url`.
- Producers, consumers, consumer groups, or Kafka Streams topologies targeting a
  self-managed cluster.

## Guidance

### 1. Prefer Azure Event Hubs

- Target **Event Hubs Standard or Premium** with the **Kafka endpoint** for
  lift-and-shift, and the **native Azure SDK** for modernized code.
- Choose the path deliberately:
  - *Kafka endpoint*: minimal code change, keeps `kafka-clients`, only endpoint
    and authentication change. Use when the migration window is short or the app
    uses Kafka Streams/Connect.
  - *Azure SDK* (`azure-messaging-eventhubs`, `Azure.Messaging.EventHubs`): the
    preferred long-term target. Required for `DefaultAzureCredential`, Azure
    Schema Registry, and first-class Azure Monitor integration.
- Map Kafka concepts explicitly:

  | Kafka | Event Hubs |
  | --- | --- |
  | Cluster | Event Hubs namespace |
  | Topic | Event hub |
  | Partition | Partition |
  | Consumer group | Consumer group |
  | Offset | Offset / sequence number (checkpoint store) |
  | Retention | Retention period (namespace/hub setting) |

### 2. Prefer Managed Identity authentication

- Use `DefaultAzureCredential` (Azure SDK) or the
  `OAUTHBEARER` SASL mechanism backed by a managed identity token callback
  (Kafka endpoint).
- Assign the least-privileged built-in roles on the namespace or hub scope:
  - `Azure Event Hubs Data Sender` for producers.
  - `Azure Event Hubs Data Receiver` for consumers.
  - `Azure Event Hubs Data Owner` only for management tooling.
- See the [`azure-managed-identity`](../azure-managed-identity/skill.md) skill for
  the credential configuration details.

### 3. Favor Azure SDK patterns

- Producers: `EventHubProducerClient` (both languages), or
  `EventHubBufferedProducerClient` where fire-and-forget batching is acceptable.
- Consumers: `EventProcessorClient`, built with `EventProcessorClientBuilder`
  and a `BlobCheckpointStore` in Java, or constructed with a
  `BlobContainerClient` checkpoint container in .NET.
- Batch with `EventDataBatch` instead of per-message sends.
- Register clients as singletons in DI; they are thread-safe and expensive to
  create.
- Use the SDK retry options instead of hand-rolled retry loops; do not swallow
  non-transient failures (`EventHubsException.isTransient() == false`) — let
  them surface.

### 4. Avoid hardcoded connection strings

- Never emit `Endpoint=sb://...;SharedAccessKey=...` into source, appsettings,
  `application.yml`, Helm values, or Terraform variables.
- Configuration must supply only the **fully qualified namespace** and **event
  hub name**; the credential comes from the platform identity.
- If a SAS connection string is unavoidable during a phased cutover, source it
  from Key Vault via Managed Identity and track its removal as a migration task.

### 5. Preserve event schema compatibility

- Do not change the serialized payload shape during the transport migration.
  Keep the migration to one variable at a time.
- Carry Kafka record headers across to `EventData.getProperties()` /
  `EventData.Properties`, including `content-type`, `schema-id`, and any
  correlation headers.
- If Confluent Schema Registry is in use, either keep it reachable or migrate to
  **Azure Schema Registry** in a separate, explicitly reviewed change. Register
  existing schemas first and validate `BACKWARD` compatibility before cutover.
- Preserve the `traceparent` header so distributed tracing survives the hop.

### 6. Preserve the partitioning strategy

- A Kafka producer keyed by `ProducerRecord(topic, key, value)` must become an
  Event Hubs send with `setPartitionKey(key)` / `PartitionKey = key`.
- Do **not** substitute round-robin or `partitionId`-based sends for keyed sends:
  that breaks per-key ordering guarantees the consumer may depend on.
- Keep the partition count equal to or greater than the Kafka topic's partition
  count; Event Hubs partition counts are fixed at creation for Standard tier.
- Consumer parallelism must not exceed the partition count.

## Architecture guidance

```text
                +-------------------------------+
                | Managed Identity (workload)   |
                +---------------+---------------+
                                | AAD token (no secrets)
                                v
+-----------+      +-------------------------+      +---------------------+
| Producer  |----->|  Event Hubs namespace   |----->| EventProcessorClient|
| service   |      |  hub: claims-events     |      | consumer group: cg1 |
+-----------+      |  partitions: 32         |      +----------+----------+
                   +-------------------------+                 |
                                                               v
                                                   +-----------------------+
                                                   | Blob checkpoint store |
                                                   | (Managed Identity)    |
                                                   +-----------------------+
```

- **Namespace per environment**, event hub per logical topic; do not multiplex
  unrelated event types into one hub.
- **Private Endpoint** the namespace and disable public network access.
- **Checkpointing** moves from Kafka `__consumer_offsets` to an Azure Storage
  container; provision it with the migration and grant the consumer identity
  `Storage Blob Data Contributor` on that container.
- **Capture** to ADLS/Blob replaces long Kafka retention for replay/audit
  scenarios.
- **Dead-lettering** is not built in: route poison events to a dedicated
  `*-deadletter` hub or Service Bus queue explicitly.
- **Throughput**: size Throughput Units (Standard) or Processing Units
  (Premium) from peak Kafka ingress; enable auto-inflate.

## Examples

| File | Description |
| --- | --- |
| [`examples/kafka-producer.java`](examples/kafka-producer.java) | Legacy `kafka-clients` producer with SASL/PLAIN credentials in configuration. |
| [`examples/eventhub-producer.java`](examples/eventhub-producer.java) | Modernized `EventHubProducerClient` with `DefaultAzureCredential`, preserved headers and partition key. |

### .NET migration

Before — `Confluent.Kafka`:

```csharp
var config = new ProducerConfig
{
    BootstrapServers = _settings.BootstrapServers,
    SecurityProtocol = SecurityProtocol.SaslSsl,
    SaslMechanism = SaslMechanism.Plain,
    SaslUsername = _settings.ApiKey,
    SaslPassword = _settings.ApiSecret // hardcoded secret
};

using var producer = new ProducerBuilder<string, string>(config).Build();

await producer.ProduceAsync("claims-events", new Message<string, string>
{
    Key = claim.ClaimReference,          // partitioning key
    Value = JsonSerializer.Serialize(claim),
    Headers = new Headers
    {
        { "content-type", Encoding.UTF8.GetBytes("application/json") },
        { "schema-id", Encoding.UTF8.GetBytes(schemaId) }
    }
});
```

After — `Azure.Messaging.EventHubs`:

```csharp
// Registered once in DI; the client is thread-safe and expensive to create.
services.AddSingleton(sp =>
{
    var options = sp.GetRequiredService<IOptions<EventHubOptions>>().Value;

    return new EventHubProducerClient(
        options.FullyQualifiedNamespace, // e.g. caldova-prod.servicebus.windows.net
        options.EventHubName,            // e.g. claims-events
        new DefaultAzureCredential());   // no secrets, no connection string
});
```

Publishing, with the singleton `EventHubProducerClient` injected as `_producer`:

```csharp
// Partition key preserved => per-claim ordering preserved.
using var batch = await _producer.CreateBatchAsync(
    new CreateBatchOptions { PartitionKey = claim.ClaimReference },
    cancellationToken);

// Payload shape is unchanged: schema compatibility is preserved.
var eventData = new EventData(JsonSerializer.SerializeToUtf8Bytes(claim));
eventData.ContentType = "application/json";
eventData.Properties["schema-id"] = schemaId;
eventData.Properties["correlation-id"] = context.CorrelationId;

// Trace context travels with the event, exactly as it did in the Kafka header.
eventData.Properties["traceparent"] = Activity.Current?.Id;

if (!batch.TryAdd(eventData))
{
    throw new InvalidOperationException(
        $"Claim event exceeds the maximum Event Hubs batch size. CorrelationId={context.CorrelationId}");
}

await _producer.SendAsync(batch, cancellationToken);
```

Consumer side (.NET) uses `EventProcessorClient` with a Blob checkpoint
container, both constructed with `DefaultAzureCredential`:

```csharp
var checkpointContainer = new BlobContainerClient(
    new Uri("https://caldovaprod.blob.core.windows.net/claims-checkpoints"),
    new DefaultAzureCredential());

var processor = new EventProcessorClient(
    checkpointContainer,
    consumerGroup: "claims-projection",   // same name as the Kafka consumer group
    fullyQualifiedNamespace: options.FullyQualifiedNamespace,
    eventHubName: options.EventHubName,
    credential: new DefaultAzureCredential());
```

## Validation checklist

- [ ] No `bootstrap.servers`, `sasl.jaas.config`, or Kafka broker credentials
      remain in source or configuration.
- [ ] No Event Hubs connection string or `SharedAccessKey` appears anywhere in
      the repository or deployment manifests.
- [ ] Clients authenticate with `DefaultAzureCredential` / Managed Identity and
      the identity holds only Data Sender or Data Receiver roles.
- [ ] Configuration exposes only the fully qualified namespace and hub name.
- [ ] Producer partition key maps 1:1 from the previous Kafka message key.
- [ ] Event Hubs partition count >= source Kafka topic partition count.
- [ ] Serialized payload shape is byte-compatible with the previous producer.
- [ ] Kafka record headers (including `traceparent` and schema identifiers) are
      carried into `EventData` properties.
- [ ] Consumer group names and consumer parallelism are preserved.
- [ ] A checkpoint store (Blob container) is provisioned and the consumer
      identity has `Storage Blob Data Contributor` on it.
- [ ] Poison-message handling routes to an explicit dead-letter destination.
- [ ] Clients are registered as singletons and disposed on shutdown.
- [ ] No PII is added to event properties or telemetry (see the
      [`pii-handling`](../pii-handling/skill.md) skill).
- [ ] Integration tests run against the target namespace before cutover.
