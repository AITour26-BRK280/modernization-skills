# PII Handling in Logs and Telemetry

## Goal

Prevent patient, member, healthcare, customer, and personally identifiable
information (PII/PHI) from being written to logs, traces, metrics, exception
messages, or any other telemetry sink.

Modernized code must remain fully diagnosable without ever emitting a value that
can identify a person.

## When to apply this skill

Apply this skill whenever the agent:

- Adds, moves, or rewrites logging statements.
- Migrates a logging framework (for example `log4net` or `System.Diagnostics` to
  `Microsoft.Extensions.Logging`, or Log4j 1.x to Log4j 2 / SLF4J).
- Introduces OpenTelemetry tracing, metrics, or log exporters.
- Adds exception handling, middleware, filters, or interceptors that serialize
  request or response payloads.
- Serializes a domain object (patient, member, claim, subscriber, account) into a
  diagnostic message.

## Rules

### Never log the following values

| Field | Typical property names |
| --- | --- |
| Social Security Number | `SSN`, `SocialSecurityNumber`, `Ssn`, `TaxId` |
| Medical Record Number | `MRN`, `MedicalRecordNumber` |
| Patient identifier | `PatientId`, `PatientIdentifier`, `PatientKey` |
| Member identifier | `MemberId`, `SubscriberId`, `MemberNumber` |
| Email address | `EmailAddress`, `Email`, `ContactEmail` |
| Date of birth | `DateOfBirth`, `DOB`, `BirthDate` |
| Account identifiers | `AccountNumber`, `AccountId`, `PolicyNumber`, `ClaimNumber`, `CardNumber` |

The same restriction applies to names, addresses, phone numbers, biometric data,
and any free-text clinical note field.

Additional prohibitions:

- Never log a whole request body, response body, or domain entity via
  `JsonSerializer.Serialize(...)`, `ToString()`, `@entity`, or `{}` placeholders.
- Never place PII in a trace/span name, span attribute, metric dimension, or
  exception message.
- Never "mask" by logging a partial value (for example the last four SSN digits).
  Partial identifiers are still PII under HIPAA safe-harbor rules.

### Always allowed

These identifiers are safe, non-reversible, and required for diagnosability:

- Correlation ID (`CorrelationId`)
- Request ID (`RequestId`)
- Trace ID (`TraceId`) and Span ID (`SpanId`)
- Operation ID (`OperationId`)
- Tenant, environment, region, service name, and version
- Non-identifying counts, durations, status codes, and result enums

### Correct remediation pattern

1. Remove the PII value from the message and structured properties.
2. Replace it with a correlation identifier that is already flowing through the
   request (or with a surrogate/pseudonymous key that cannot be reversed outside
   the system of record).
3. Keep the operational fact being logged (the "what happened"), drop the
   "who it happened to".
4. If the identity truly must be resolvable for support, log only a
   non-reversible surrogate key and record the lookup in the audit store, never
   in application logs.

## Target technologies

### .NET

- Use `Microsoft.Extensions.Logging` with structured message templates.
- Use `ILogger.BeginScope` to attach `CorrelationId` once per request instead of
  repeating identifiers in each message.
- Never interpolate strings (`$"..."`) into `ILogger` calls; interpolation hides
  the value from redaction analyzers and defeats structured logging. (Building an
  exception message from a correlation ID with interpolation is fine — the
  prohibition applies to log message templates.)
- Register a redaction-aware enricher or `ILogger` filter in
  `Program.cs`/`Startup.cs` as defense in depth.

### Java

- Use SLF4J parameterized logging (`log.info("...{}", value)`).
- Put `correlationId` in MDC once per request via a servlet filter or
  interceptor; never put PHI in MDC.
- Do not log entity objects directly; Lombok `@ToString` on JPA entities is a
  common source of PHI leakage — annotate PHI fields with
  `@ToString.Exclude`.

### OpenTelemetry

See the [`open-telemetry-standard`](../open-telemetry-standard/SKILL.md) skill
for the full observability configuration these rules apply to.

- Span attributes are exported to third-party backends: treat them exactly like
  logs.
- Allowed attributes: `enduser.pseudo.id` (surrogate only), `correlation.id`,
  `operation.id`, `http.route`, `messaging.system`, `db.operation`.
- Disallowed attributes: anything carrying the values in the table above,
  `http.request.body`, `db.statement` when it contains bound PHI parameters.
- Configure a span processor that drops or hashes unexpected attributes before
  export.

## Examples

See [`examples/before.cs`](examples/before.cs) for the non-compliant pattern and
[`examples/after.cs`](examples/after.cs) for the corrected implementation. The
examples are written in .NET; the same before/after transformation applies
verbatim to the Java and OpenTelemetry guidance above (replace the
`ILogger` scope with an MDC `correlationId` entry set by a request filter).

Note that `after.cs` builds its rethrown exception message with string
interpolation. That is intentional: the "no interpolation" rule applies to
`ILogger` message templates, and the interpolated value is a correlation ID.

## Validation checklist

- [ ] No log, span, metric, or exception message contains a value from the
      prohibited table.
- [ ] No log statement serializes a whole request, response, or domain entity.
- [ ] Every log statement that previously identified a person now carries a
      correlation, request, trace, or operation ID instead.
- [ ] Structured logging templates are used (no string interpolation or
      concatenation).
- [ ] Correlation ID is established once per request and flows to downstream
      calls via `traceparent`.
- [ ] OpenTelemetry attribute allow-list is configured and enforced.
- [ ] Existing tests still pass and log-assertion tests were updated, not
      deleted.
