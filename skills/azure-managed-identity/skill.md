# Azure Managed Identity

## Goal

Replace secrets, passwords, API keys, and connection strings with Microsoft Entra
Managed Identity so that no application in the portfolio stores a long-lived
credential in source control, configuration, or a pipeline variable.

## When to apply this skill

Apply this skill whenever the agent encounters:

- `ConnectionStrings` entries containing a shared access key, an account key, a
  SQL user password, a client secret, or a `sig` SAS token parameter.
- `new DefaultAzureCredential()` alternatives such as `ClientSecretCredential`,
  `StorageSharedKeyCredential`, or `AzureSasCredential`.
- `ClientSecret`, `ApiKey`, `SasToken`, or similar keys in `appsettings.json`,
  `application.yml`, `web.config`, Helm values, or environment variables.
- Any Azure SDK client constructed from a connection string.

## Rules

1. **Every Azure data-plane client authenticates with a token credential.**
   Use `DefaultAzureCredential` (or `ManagedIdentityCredential` in production-only
   code paths). Never construct a client from a connection string or account key.
2. **Configuration carries endpoints, not credentials.** Supply the fully
   qualified namespace, account URI, or vault URI; the identity supplies the rest.
3. **Prefer user-assigned managed identity** for workloads that span multiple
   resources or that are recreated frequently, and pass its client ID explicitly
   so credential resolution is deterministic.
4. **Assign least-privileged built-in RBAC roles** at the narrowest scope that
   works (resource, then resource group, never subscription).
5. **Reuse credential and client instances.** Register them as singletons; the
   credential caches tokens and refreshes them automatically. Do not call
   `GetToken` manually.
6. **Remove the secret, do not merely stop using it.** Delete the setting, rotate
   or revoke the credential, and remove it from Key Vault, pipeline variables,
   and any local `.env`/user-secrets file.
7. **Never log tokens, credentials, or the identity's client secret material.**
   See the [`pii-handling`](../pii-handling/skill.md) skill for the logging rules.

## Azure SDK authentication

`DefaultAzureCredential` resolves credentials in order (environment,
workload identity, managed identity, developer tooling), which makes the same
code work locally and in Azure.

.NET:

```csharp
// Program.cs - one credential instance shared by every Azure client.
var credential = new DefaultAzureCredential(new DefaultAzureCredentialOptions
{
    ManagedIdentityClientId = builder.Configuration["Azure:ManagedIdentityClientId"],
    ExcludeInteractiveBrowserCredential = true
});

builder.Services.AddSingleton<TokenCredential>(credential);

builder.Services.AddAzureClients(clients =>
{
    clients.UseCredential(credential);
    // Individual clients registered below inherit the credential.
});
```

Java / Spring Boot:

```java
@Bean
public TokenCredential azureCredential(
        @Value("${azure.managed-identity-client-id:}") String clientId) {

    DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();

    if (!clientId.isBlank()) {
        builder.managedIdentityClientId(clientId);
    }

    return builder.build();
}
```

Kubernetes workloads use **Entra Workload Identity**: annotate the service
account with the identity client ID and federate it to the AKS OIDC issuer.
`DefaultAzureCredential` then picks up `WorkloadIdentityCredential`
automatically — no code change between App Service, Container Apps, VM, and AKS.

## Key Vault access

Before:

```csharp
var secret = _configuration["ClientSecrets:PaymentsApi"]; // secret in config
```

After:

```csharp
builder.Services.AddSingleton(sp => new SecretClient(
    new Uri(builder.Configuration["Azure:KeyVaultUri"]!),   // https://caldova-prod-kv.vault.azure.net/
    sp.GetRequiredService<TokenCredential>()));

// Or load the whole vault into configuration at startup:
builder.Configuration.AddAzureKeyVault(
    new Uri(builder.Configuration["Azure:KeyVaultUri"]!),
    credential);
```

- Role: `Key Vault Secrets User` (read) or `Key Vault Certificate User`.
- Use **RBAC** authorization on the vault, not legacy access policies.
- Key Vault should hold only third-party secrets that have no Entra equivalent.
  Azure-to-Azure calls must use Managed Identity directly, not a vaulted
  connection string.

## Event Hubs

```csharp
builder.Services.AddSingleton(sp => new EventHubProducerClient(
    fullyQualifiedNamespace: "caldova-prod.servicebus.windows.net",
    eventHubName: "claims-events",
    credential: sp.GetRequiredService<TokenCredential>()));
```

- Producer role: `Azure Event Hubs Data Sender`.
- Consumer role: `Azure Event Hubs Data Receiver` (plus
  `Storage Blob Data Contributor` on the checkpoint container).
- Never `EventHubProducerClient(connectionString)`.
- See the [`kafka-to-eventhubs`](../kafka-to-eventhubs/skill.md) skill for the
  full migration pattern.

## Storage Accounts

```csharp
builder.Services.AddSingleton(sp => new BlobServiceClient(
    new Uri("https://caldovaprod.blob.core.windows.net"),
    sp.GetRequiredService<TokenCredential>()));
```

- Roles: `Storage Blob Data Reader`, `Storage Blob Data Contributor`,
  `Storage Queue Data Contributor`, `Storage Table Data Contributor`.
- Set `allowSharedKeyAccess = false` on the account once every consumer has
  migrated; this makes regressions fail fast.
- Replace SAS URLs with **user delegation SAS** generated from the managed
  identity when a short-lived, shareable URL is genuinely required.
- Azure Files SMB still requires a key or Entra Kerberos — document the exception
  explicitly if it cannot be removed.

## Service Bus

```csharp
builder.Services.AddSingleton(sp => new ServiceBusClient(
    "caldova-prod.servicebus.windows.net",
    sp.GetRequiredService<TokenCredential>()));
```

- Sender role: `Azure Service Bus Data Sender`.
- Receiver role: `Azure Service Bus Data Receiver`.
- Scope the assignment to the queue or topic, not the namespace, when a workload
  only touches one entity.

## Other data planes

| Service | Client | Role |
| --- | --- | --- |
| Azure SQL | `SqlConnection` with `Authentication=Active Directory Default` | contained DB user mapped to the identity |
| Cosmos DB | `CosmosClient(accountEndpoint, credential)` | `Cosmos DB Built-in Data Contributor` |
| App Configuration | `ConfigurationClient(endpoint, credential)` | `App Configuration Data Reader` |
| Azure OpenAI | `AzureOpenAIClient(endpoint, credential)` | `Cognitive Services OpenAI User` |

## Validation checklist

- [ ] No connection string, account key, SAS token, or client secret remains in
      source, configuration, manifests, or pipeline variables.
- [ ] Every Azure client is constructed from an endpoint plus a
      `TokenCredential`.
- [ ] A single credential instance is registered and reused; clients are
      singletons.
- [ ] User-assigned identity client ID is supplied explicitly where one is used.
- [ ] Least-privileged built-in roles are assigned at the narrowest workable
      scope, and are captured in infrastructure-as-code.
- [ ] Local development works through developer credentials (Azure CLI /
      Visual Studio) without secrets.
- [ ] `allowSharedKeyAccess` is disabled on migrated storage accounts and local
      auth is disabled on migrated Service Bus / Event Hubs namespaces.
- [ ] Retired secrets are rotated or revoked, not just unreferenced.
- [ ] No credential or token value is written to logs or telemetry.
