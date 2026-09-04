---
name: open-telemetry-standard
description: "Enforces the organization-wide observability standard: OpenTelemetry tracing, metrics, logging, correlation, and distributed context propagation with consistent resource attributes and OTLP export to Azure Monitor."
---

# OpenTelemetry Standard

## Goal

Enforce a single, organization-wide observability standard so that every
modernized service emits traces, metrics, and logs with consistent naming,
consistent resource attributes, and an unbroken correlation chain from the edge
gateway to the database.

## When to apply this skill

Apply this skill whenever the agent:

- Modernizes a service that uses a bespoke or legacy telemetry stack
  (`System.Diagnostics.Trace`, `TelemetryClient`, Log4j appenders to disk,
  StatsD, custom timing wrappers).
- Adds a new service, worker, or function to the portfolio.
- Introduces or migrates a messaging, HTTP, or database integration where trace
  context must propagate.

## The standard

### 1. Signals

All three signals are mandatory. A service that only exports logs is not
compliant.

| Signal | Requirement |
| --- | --- |
| Traces | Every inbound request, outbound call, and message handler is a span. |
| Metrics | RED metrics (rate, errors, duration) plus runtime and business metrics. |
| Logs | Structured, correlated, exported through the OpenTelemetry logging pipeline. |

### 2. Resource attributes

Every signal carries the same resource attributes, set once at startup:

| Attribute | Example |
| --- | --- |
| `service.name` | `caldova-claims-api` |
| `service.namespace` | `caldova.claims` |
| `service.version` | `2026.4.1` |
| `service.instance.id` | pod or instance name |
| `deployment.environment` | `prod`, `stage`, `dev` |
| `cloud.region` | `eastus2` |

`service.name` must match the repository/deployment name exactly and must never
be defaulted to `unknown_service`.

### 3. Export

- Export via **OTLP** to the collector; do not couple application code to a
  vendor exporter. Azure Monitor is reached through the collector or the
  Azure Monitor OpenTelemetry Distro.
- Endpoint and headers come from `OTEL_EXPORTER_OTLP_ENDPOINT` and standard
  `OTEL_*` environment variables — not from hardcoded values.
- Use batch processors in all environments except local debugging.

### 4. Sampling

- Use a parent-based sampler so a sampling decision made at the edge is honored
  end to end.
- Default production ratio: 10%. Always sample errors and any request that took
  longer than the SLO.
- Never sample independently per service — that produces broken traces.

### 5. Naming

- Span names are low-cardinality: `GET /claims/{claimId}`, not
  `GET /claims/8f21...`.
- Metric names use the OpenTelemetry semantic conventions
  (`http.server.request.duration`, `messaging.client.published.messages`).
- Custom business metrics are prefixed with the domain: `claims.submitted`,
  `claims.rejected`.
- Attribute values must be bounded; never use an identifier as a metric
  dimension.

### 6. Correlation

- The W3C `traceparent` (and `tracestate`) header is the single correlation
  mechanism. Do not invent bespoke `X-Correlation-Id` propagation for new code;
  where a legacy header exists, map it into a span attribute and keep
  `traceparent` authoritative.
- Every log record carries `trace_id` and `span_id`, injected automatically by
  the logging bridge — not by hand.
- Messaging producers inject `traceparent` into message headers/properties;
  consumers extract it and link or continue the trace.
- Background jobs and scheduled work start a root span and log the resulting
  operation ID so the run is discoverable.

### 7. Distributed tracing coverage

Instrument at minimum: inbound HTTP/gRPC, outbound HTTP, database calls,
messaging publish/receive, cache access, and any external SaaS call. Prefer
auto-instrumentation libraries over manual spans; add manual spans only for
meaningful business operations.

### 8. Privacy

Span attributes and metric dimensions are exported to third-party backends and
are subject to exactly the same restrictions as logs. Never attach PII/PHI —
see the [`pii-handling`](../pii-handling/SKILL.md) skill. Redact query
parameters and disable `db.statement` capture where statements may embed PHI.

## Sample configuration — .NET

`Program.cs`:

```csharp
using Azure.Monitor.OpenTelemetry.AspNetCore;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

var builder = WebApplication.CreateBuilder(args);

const string ServiceName = "caldova-claims-api";

builder.Services.AddOpenTelemetry()
    .ConfigureResource(resource => resource
        .AddService(
            serviceName: ServiceName,
            serviceNamespace: "caldova.claims",
            serviceVersion: typeof(Program).Assembly.GetName().Version?.ToString(),
            serviceInstanceId: Environment.MachineName)
        .AddAttributes(new Dictionary<string, object>
        {
            ["deployment.environment"] = builder.Environment.EnvironmentName,
            ["cloud.region"] = builder.Configuration["Azure:Region"] ?? "unknown"
        }))
    .WithTracing(tracing => tracing
        .SetSampler(new ParentBasedSampler(new TraceIdRatioBasedSampler(0.1)))
        .AddAspNetCoreInstrumentation(o =>
        {
            o.RecordException = true;
            o.Filter = context => !context.Request.Path.StartsWithSegments("/health");
        })
        .AddHttpClientInstrumentation()
        .AddSqlClientInstrumentation(o => o.SetDbStatementForText = false) // may contain PHI
        .AddSource("Caldova.Claims.Api")                                   // manual business spans
        .AddOtlpExporter())
    .WithMetrics(metrics => metrics
        .AddAspNetCoreInstrumentation()
        .AddHttpClientInstrumentation()
        .AddRuntimeInstrumentation()
        .AddMeter("Caldova.Claims.Api")
        .AddOtlpExporter());

builder.Logging.AddOpenTelemetry(logging =>
{
    logging.IncludeScopes = true;        // correlation scope properties
    logging.IncludeFormattedMessage = true;
    logging.ParseStateValues = true;
    logging.AddOtlpExporter();
});

// Azure Monitor distro alternative (configures traces, metrics and logs at once):
// builder.Services.AddOpenTelemetry().UseAzureMonitor();

var app = builder.Build();
```

Manual instrumentation:

```csharp
public sealed class ClaimTelemetry
{
    public static readonly ActivitySource Source = new("Caldova.Claims.Api");
    private static readonly Meter Meter = new("Caldova.Claims.Api");

    public static readonly Counter<long> ClaimsSubmitted =
        Meter.CreateCounter<long>("claims.submitted", unit: "{claim}");

    public static readonly Histogram<double> SubmissionDuration =
        Meter.CreateHistogram<double>("claims.submission.duration", unit: "ms");
}
```

Used from inside the business operation:

```csharp
using var activity = ClaimTelemetry.Source.StartActivity("Claim.Submit", ActivityKind.Internal);
activity?.SetTag("claim.type", claimType);          // low cardinality, no PHI
ClaimTelemetry.ClaimsSubmitted.Add(1, new KeyValuePair<string, object?>("claim.type", claimType));
```

## Sample configuration — Java

`pom.xml` (Spring Boot 3):

```xml
<dependency>
  <groupId>io.opentelemetry.instrumentation</groupId>
  <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

`application.properties` (dotted keys avoid the YAML nesting ambiguity around
`otel.traces.sampler` and `otel.traces.sampler.arg`):

```properties
otel.service.name=caldova-claims-worker

otel.resource.attributes=service.namespace=caldova.claims,\
  service.version=${APP_VERSION:unknown},\
  deployment.environment=${ENVIRONMENT:dev},\
  cloud.region=${AZURE_REGION:unknown}

otel.traces.sampler=parentbased_traceidratio
otel.traces.sampler.arg=0.1

otel.traces.exporter=otlp
otel.metrics.exporter=otlp
otel.logs.exporter=otlp

otel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}
otel.exporter.otlp.protocol=grpc

otel.instrumentation.jdbc.statement-sanitizer.enabled=true
```

Zero-code agent alternative (preferred for legacy applications):

```bash
java -javaagent:/opt/opentelemetry-javaagent.jar \
     -Dotel.service.name=caldova-claims-worker \
     -Dotel.traces.sampler=parentbased_traceidratio \
     -Dotel.traces.sampler.arg=0.1 \
     -jar claims-worker.jar
```

Manual instrumentation and log correlation:

```java
private static final Tracer TRACER =
        GlobalOpenTelemetry.getTracer("com.caldova.claims.worker");

private static final LongCounter CLAIMS_PROCESSED =
        GlobalOpenTelemetry.getMeter("com.caldova.claims.worker")
                .counterBuilder("claims.processed")
                .setUnit("{claim}")
                .build();

Span span = TRACER.spanBuilder("Claim.Process").startSpan();

try (Scope scope = span.makeCurrent()) {
    // trace_id / span_id are injected into every SLF4J record inside this scope.
    log.info("Processing claim batch. BatchSize={}", batch.size());
    process(batch);
    CLAIMS_PROCESSED.add(batch.size(), Attributes.of(AttributeKey.stringKey("claim.type"), claimType));
} catch (Exception ex) {
    span.recordException(ex);
    span.setStatus(StatusCode.ERROR);
    throw ex;
} finally {
    span.end();
}
```

Logback pattern for correlated console output:

```xml
<pattern>%d{ISO8601} %-5level [${OTEL_SERVICE_NAME:-app},%X{trace_id},%X{span_id}] %logger{36} - %msg%n</pattern>
```

## Validation checklist

- [ ] Traces, metrics, and logs are all configured and exported.
- [ ] `service.name`, `service.namespace`, `service.version`, and
      `deployment.environment` are set on the resource for every signal.
- [ ] Export uses OTLP with endpoint configuration from `OTEL_*` environment
      variables; no vendor exporter is hardcoded in application code.
- [ ] A parent-based sampler is configured with the standard ratio.
- [ ] Auto-instrumentation covers inbound HTTP, outbound HTTP, database, and
      messaging.
- [ ] `traceparent` propagates across HTTP and messaging boundaries, including
      Event Hubs message properties.
- [ ] Log records carry `trace_id` and `span_id` automatically.
- [ ] Span and metric names are low cardinality; identifiers are never used as
      metric dimensions.
- [ ] Database statement capture is sanitized or disabled where statements may
      contain PHI.
- [ ] No PII/PHI appears in any span attribute, metric dimension, or log record.
- [ ] Health and readiness endpoints are excluded from tracing to control noise.
- [ ] The legacy telemetry stack is removed, not left running in parallel.
