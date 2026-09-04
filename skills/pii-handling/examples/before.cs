// NON-COMPLIANT EXAMPLE - do not copy this pattern.
//
// Caldova.Claims.Api - legacy claim intake handler.
// Every logging statement below leaks PHI/PII into the log pipeline and into
// Application Insights, where it is retained for 90 days and readable by any
// operator with Log Analytics access.

using System;
using System.Text.Json;
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

    public async Task<ClaimResult> HandleAsync(ClaimSubmission submission, CancellationToken cancellationToken)
    {
        // VIOLATION: serializes the entire submission, including SSN, MRN and DOB.
        _logger.LogInformation("Received claim submission: {Payload}",
            JsonSerializer.Serialize(submission));

        // VIOLATION: string interpolation hides values from redaction analyzers
        // and logs PatientId, MemberId and DateOfBirth.
        _logger.LogInformation(
            $"Processing claim for patient {submission.PatientId} / member {submission.MemberId}, DOB {submission.DateOfBirth:d}");

        // VIOLATION: partial masking is still PII under HIPAA safe-harbor rules.
        _logger.LogDebug("Verifying identity with SSN ending {Last4}",
            submission.Ssn[^4..]);

        try
        {
            var result = await _claims.SubmitAsync(submission, cancellationToken);

            // VIOLATION: MRN, account number and email address in a success message.
            _logger.LogInformation(
                "Claim accepted. MRN={Mrn} Account={AccountNumber} Notify={EmailAddress}",
                submission.MedicalRecordNumber,
                submission.AccountNumber,
                submission.EmailAddress);

            return result;
        }
        catch (Exception ex)
        {
            // VIOLATION: PHI embedded in the exception message is exported with
            // the stack trace to every telemetry backend.
            _logger.LogError(ex,
                "Failed to submit claim for {PatientId} ({EmailAddress})",
                submission.PatientId,
                submission.EmailAddress);

            throw new InvalidOperationException(
                $"Claim submission failed for MRN {submission.MedicalRecordNumber}", ex);
        }
    }
}
