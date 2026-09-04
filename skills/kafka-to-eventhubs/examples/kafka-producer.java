// LEGACY EXAMPLE - Caldova claims platform, self-managed Apache Kafka producer.
//
// Problems addressed by the kafka-to-eventhubs skill:
//   1. Broker credentials supplied as a username/password in configuration.
//   2. Bootstrap servers point at a self-managed cluster.
//   3. Producer lifecycle is managed by hand instead of DI/SDK patterns.
//
// The message key (claimReference) and the record headers are the two things
// that MUST be preserved by the migration.

package com.caldova.claims.messaging.legacy;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Declared package-private so this illustrative file can keep the skills-library
// file naming convention rather than the Java public-class file-name rule.
class ClaimEventProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventProducer.class);
    private static final String TOPIC = "claims-events";

    private final KafkaProducer<String, String> producer;

    ClaimEventProducer(KafkaSettings settings) {
        Properties props = new Properties();

        // Self-managed broker endpoint.
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        // Shared secret authentication - the pattern the migration removes.
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism", "PLAIN");
        // The JAAS entry embeds a shared API key/secret pair supplied through
        // configuration - exactly the credential pattern the migration removes.
        props.put("sasl.jaas.config", settings.getPlainLoginModuleJaasConfig());

        this.producer = new KafkaProducer<>(props);
    }

    void publish(ClaimEvent event, String payloadJson, String schemaId, String correlationId,
                        String traceparent) {
        // The message key drives partition assignment and therefore per-claim
        // ordering. It must survive the migration unchanged.
        ProducerRecord<String, String> record =
                new ProducerRecord<>(TOPIC, event.getClaimReference(), payloadJson);

        record.headers().add("content-type", "application/json".getBytes(StandardCharsets.UTF_8));
        record.headers().add("schema-id", schemaId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));

        producer.send(record, (RecordMetadata metadata, Exception exception) -> {
            if (exception != null) {
                log.error("Failed to publish claim event. CorrelationId={}", correlationId, exception);
                return;
            }

            log.info("Published claim event. CorrelationId={} Partition={} Offset={}",
                    correlationId, metadata.partition(), metadata.offset());
        });
    }

    @Override
    public void close() {
        producer.close();
    }
}
