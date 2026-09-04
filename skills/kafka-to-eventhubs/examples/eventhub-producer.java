// MODERNIZED EXAMPLE - Caldova claims platform, Azure Event Hubs producer.
//
// Migration of ClaimEventProducer (see kafka-producer.java) applying the
// kafka-to-eventhubs skill:
//   1. Azure SDK client (EventHubProducerClient) instead of kafka-clients.
//   2. DefaultAzureCredential / Managed Identity instead of a SASL secret.
//   3. Configuration carries only the namespace and hub name - no connection
//      string, no SharedAccessKey.
//   4. The Kafka message key becomes the Event Hubs partition key, so per-claim
//      ordering is preserved.
//   5. Record headers become EventData application properties, so the event
//      schema and distributed trace context stay compatible.

package com.caldova.claims.messaging;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Declared package-private so this illustrative file can keep the skills-library
// file naming convention rather than the Java public-class file-name rule.
class ClaimEventProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventProducer.class);

    private final EventHubProducerClient producer;

    /**
     * Register this type as a singleton: the client is thread-safe, multiplexes
     * a single AMQP connection, and is expensive to create.
     *
     * @param settings supplies only the fully qualified namespace
     *                 (for example {@code caldova-prod.servicebus.windows.net})
     *                 and the event hub name (for example {@code claims-events}).
     */
    ClaimEventProducer(EventHubSettings settings) {
        // Managed Identity in Azure, developer credentials locally. The client
        // ID is only set when a user-assigned identity is configured; otherwise
        // the system-assigned identity is used.
        DefaultAzureCredentialBuilder credentialBuilder = new DefaultAzureCredentialBuilder();

        if (settings.getManagedIdentityClientId() != null
                && !settings.getManagedIdentityClientId().isBlank()) {
            credentialBuilder.managedIdentityClientId(settings.getManagedIdentityClientId());
        }

        DefaultAzureCredential credential = credentialBuilder.build();

        AmqpRetryOptions retryOptions = new AmqpRetryOptions()
                .setMode(AmqpRetryMode.EXPONENTIAL)
                .setMaxRetries(5)
                .setDelay(Duration.ofMillis(500))
                .setTryTimeout(Duration.ofSeconds(30));

        this.producer = new EventHubClientBuilder()
                .credential(
                        settings.getFullyQualifiedNamespace(),
                        settings.getEventHubName(),
                        credential)
                .retryOptions(retryOptions)
                .buildProducerClient();
    }

    void publish(ClaimEvent event, String payloadJson, String schemaId, String correlationId,
                        String traceparent) {
        // The former Kafka message key becomes the partition key: same key,
        // same partition, same ordering guarantee.
        CreateBatchOptions batchOptions = new CreateBatchOptions()
                .setPartitionKey(event.getClaimReference());

        EventDataBatch batch = producer.createBatch(batchOptions);

        // Payload bytes are unchanged - the same UTF-8 encoding the Kafka
        // StringSerializer produced - so downstream consumers and the registered
        // schema remain compatible.
        EventData eventData = new EventData(payloadJson.getBytes(StandardCharsets.UTF_8));
        eventData.setContentType("application/json");
        eventData.getProperties().put("schema-id", schemaId);
        eventData.getProperties().put("correlation-id", correlationId);

        // Distributed tracing survives the hop: the W3C trace context travels as
        // an application property, exactly as it did in the Kafka record header.
        eventData.getProperties().put("traceparent", traceparent);

        if (!batch.tryAdd(eventData)) {
            throw new IllegalStateException(
                    "Claim event exceeds the maximum Event Hubs batch size. CorrelationId=" + correlationId);
        }

        producer.send(batch);

        // Only correlation identifiers are logged - never claim or patient data.
        log.info("Published claim event. CorrelationId={} PartitionKey={}",
                correlationId, event.getClaimReference());
    }

    @Override
    public void close() {
        producer.close();
    }
}
