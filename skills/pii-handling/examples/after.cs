// COMPLIANT EXAMPLE - target pattern produced by the pii-handling skill.
//
// Caldova.Claims.Api - modernized claim intake handler.
// The same operational facts are logged, but every person-identifying value is
// replaced by a correlation, request, or operation identifier. Diagnosability is
// preserved: support can join a correlation ID to the audit store (the system of
// record) to resolve identity under an access-controlled workflow.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Caldova.Claims.Api.Handlers;

public sealed class ClaimIntakeHandler
{
    private readonly ILogger<ClaimIntakeHandler> _logger;
    private readonly IClaimRepository _claims;

    public ClaimIntakeHandler(ILogger<ClaimIntakeHandler> logger, IClaimRepository claims)
    {
        _logger = logger;
        _claims = claims;
    }

    public async Task<ClaimResult> HandleAsync(
        ClaimSubmission submission,
        RequestContext context,
        CancellationToken cancellationToken)
    {
        // Correlation identifiers are attached once per request via a logging
        // scope, so every downstream message is joinable without repeating -
        // or leaking - any identity value.
        using var scope = _logger.BeginScope(new Dictionary<string, object?>
        {
            ["CorrelationId"] = context.CorrelationId,
            ["RequestId"] = context.RequestId,
            ["OperationId"] = context.OperationId,
            ["TraceId"] = Activity.Current?.TraceId.ToString()
        });

        // Log the operational fact, not the person: counts, kinds and routes are
        // safe dimensions.
        _logger.LogInformation(
            "Received claim submission. LineItemCount={LineItemCount} ClaimType={ClaimType}",
            submission.LineItems.Count,
            submission.ClaimType);

        try
        {
            var result = await _claims.SubmitAsync(submission, cancellationToken);

            // ClaimReference is a system-generated, non-reversible surrogate key,
            // and ElapsedMilliseconds is measured by the repository call itself -
            // both are safe operational dimensions.
            _logger.LogInformation(
                "Claim accepted. ClaimReference={ClaimReference} DurationMs={DurationMs}",
                result.ClaimReference,
                result.ElapsedMilliseconds);

            return result;
        }
        catch (ClaimValidationException ex)
        {
            // Failure reasons are enums, never free-text carrying PHI.
            _logger.LogWarning(ex,
                "Claim rejected. Reason={RejectionReason}",
                ex.RejectionReason);

            throw;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Claim submission failed unexpectedly.");

            // The exception message carries the correlation ID so support can
            // resolve identity from the audit store, never from the logs.
            throw new InvalidOperationException(
                $"Claim submission failed. CorrelationId={context.CorrelationId}", ex);
        }
    }
}

// Span attributes are exported to third-party backends, so they follow exactly
// the same allow-list as logs.
public static class ClaimTelemetry
{
    public static readonly ActivitySource Source = new("Caldova.Claims.Api");

    public static Activity? StartSubmission(RequestContext context, string claimType)
    {
        var activity = Source.StartActivity("Claim.Submit", ActivityKind.Server);

        activity?.SetTag("correlation.id", context.CorrelationId);
        activity?.SetTag("operation.id", context.OperationId);
        activity?.SetTag("claim.type", claimType);

        // Deliberately absent: patient.id, member.id, mrn, ssn, dob, email,
        // account.number, http.request.body.

        return activity;
    }
}
