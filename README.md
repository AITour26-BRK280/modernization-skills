# Modernization Skills Library

Centralized **Skills Library** for the GitHub Copilot Modernization Agent.

This repository holds reusable modernization skills that are shared across every
application and portfolio in the organization, so that a fix applied to one
service is applied the same way to all of them.

> **Rulebooks define what the organization requires.**
> **Skills define how the organization implements it.**
> **Both are required for successful modernization at scale.**

## Contents

| Skill | Category | Purpose |
| --- | --- | --- |
| [`pii-handling`](skills/pii-handling/skill.md) | security-and-compliance | Keep patient, member, and customer identifiers out of logs and telemetry. |
| [`kafka-to-eventhubs`](skills/kafka-to-eventhubs/skill.md) | cloud-migration | Standardize Apache Kafka migrations to Azure Event Hubs. |
| [`azure-managed-identity`](skills/azure-managed-identity/skill.md) | security-and-compliance | Replace secrets and connection strings with Managed Identity. |
| [`open-telemetry-standard`](skills/open-telemetry-standard/skill.md) | observability | Enforce the organization-wide observability standard. |

## Repository layout

```text
skills/
├── pii-handling/
│   ├── skill.md
│   ├── examples/
│   │   ├── before.cs
│   │   └── after.cs
│   └── metadata.json
│
├── kafka-to-eventhubs/
│   ├── skill.md
│   ├── examples/
│   │   ├── kafka-producer.java
│   │   └── eventhub-producer.java
│   └── metadata.json
│
├── azure-managed-identity/
│   ├── skill.md
│   └── metadata.json
│
├── open-telemetry-standard/
│   ├── skill.md
│   └── metadata.json
│
README.md
```

## What is a Skills Library?

A Skills Library is a version-controlled collection of **reusable modernization
instructions** that the GitHub Copilot Modernization Agent loads before it plans
or edits code.

Each skill is a small, self-contained package:

- **`skill.md`** — the instructions themselves: the goal, when the skill applies,
  the rules to follow, target technologies, worked examples, and a validation
  checklist.
- **`examples/`** — realistic before/after code that shows the agent the exact
  shape of the expected output.
- **`metadata.json`** — how the skill is discovered, categorized, and owned.

Because the library lives in one repository, an organization gets:

- **Consistency** — every application is modernized the same way.
- **Reuse** — a pattern proven on one service is immediately available to the
  whole portfolio.
- **Auditability** — the agent's guidance is reviewed, versioned, and traceable
  through pull requests.
- **Speed** — teams stop re-deriving the same migration decisions.

## Rulebooks vs Skills

Rulebooks and Skills are complementary. They answer different questions.

| | Rulebook | Skill |
| --- | --- | --- |
| Question answered | *What must be true?* | *How do we make it true?* |
| Nature | Policy, standard, constraint | Procedure, pattern, worked example |
| Typical content | "Never log an SSN", "No secrets in source" | The .NET/Java refactoring that removes the SSN from the log statement |
| Scope | Organization-wide governance | Technology- and task-specific implementation |
| Failure mode without it | Teams disagree about requirements | Teams agree on requirements but implement them ten different ways |
| Owned by | Security, architecture, compliance | Platform and domain engineering |
| Verified by | Compliance review, audit | Code review, tests, the skill's validation checklist |

A rulebook says *"personally identifiable information must never be written to
application logs."* The [`pii-handling`](skills/pii-handling/skill.md) skill shows
the agent how to rewrite an offending `ILogger` call into a correlation-ID-based
equivalent that keeps the code diagnosable.

## Connecting this repository to the GitHub Copilot Modernization Agent

1. **Publish the repository** in the organization that owns the applications to
   be modernized, and make it readable by every team that will consume it.
2. **Register it as a Skills Library.** In the Copilot Modernization Agent
   configuration for the organization (or for an individual application), add
   this repository as a skills source and point it at the `skills/` directory.
   Pin a tag or release for production use so that agent behavior is
   reproducible.
3. **Grant access.** The agent needs read access to this repository in addition
   to the target application repository. For private libraries, install the
   Copilot app on this repository as well.
4. **Select skills per assessment.** Attach the skills relevant to the workload —
   for example `kafka-to-eventhubs` plus `azure-managed-identity` for an
   integration service, and `pii-handling` plus `open-telemetry-standard` for
   every workload.
5. **Run the modernization task.** The agent loads each selected `skill.md`,
   applies its rules while planning and editing, and uses the `examples/` files
   as the target shape for generated code.
6. **Review the pull request against the skill's validation checklist.** The
   checklist at the end of each skill doubles as the reviewer's checklist.

Local and manual use is also supported: point any Copilot agent at a `skill.md`
file as additional context, or reference the skill from a repository-level
`.github/copilot-instructions.md`.

## Authoring a new skill

1. **Create the folder.** `skills/<skill-name>/`, using a lowercase,
   hyphenated name that describes the outcome (`kafka-to-eventhubs`), not the
   tool.
2. **Write `skill.md`** using the structure shared by every skill in this
   repository:
   - `# Title`
   - **Goal** — one paragraph, outcome-focused.
   - **When to apply this skill** — concrete triggers (dependencies, APIs,
     configuration keys) the agent can detect.
   - **Rules / Guidance** — imperative, testable statements. Prefer "never" and
     "always" over "consider".
   - **Target technologies** — languages, frameworks, and SDKs the skill
     applies to.
   - **Examples** — before/after code, ideally in `examples/`.
   - **Validation checklist** — the acceptance criteria for a generated pull
     request.
3. **Add `metadata.json`** with all required fields (see below).
4. **Add examples.** Before/after pairs are the highest-value part of a skill.
   Use realistic domain code, not `Foo`/`Bar`, and keep files compilable in
   spirit even though they are not built by CI.
5. **Keep it focused.** One skill, one concern. Cross-reference sibling skills
   with relative links instead of duplicating their content.
6. **Never include secrets or real data.** Examples must use fictional
   organizations, synthetic identifiers, and placeholder endpoints.
7. **Open a pull request** and request review from the owning team named in
   `metadata.json`.

### `metadata.json` schema

| Field | Type | Description |
| --- | --- | --- |
| `name` | string | Unique skill identifier; must match the folder name. |
| `description` | string | One sentence describing what the skill does; used for discovery. |
| `category` | string | Grouping, for example `security-and-compliance`, `cloud-migration`, `observability`. |
| `owner` | string | Team accountable for the skill's accuracy. |
| `tags` | string[] | Lowercase keywords: technologies, platforms, languages. |
| `version` | string | Semantic version of the skill. |

```json
{
  "name": "pii-handling",
  "description": "Prevents personally identifiable information from being written to logs or telemetry.",
  "category": "security-and-compliance",
  "owner": "caldova-platform-security",
  "tags": ["pii", "logging", "dotnet", "java"],
  "version": "1.0.0"
}
```

Bump `version` with every meaningful change: **major** for a rule change that
alters generated code, **minor** for new guidance or examples, **patch** for
clarifications and typos.

## Governance recommendations

- **Ownership.** Every skill has a named owning team in `metadata.json`. Enforce
  it with `CODEOWNERS` so the owner reviews every change.
- **Protected default branch.** Require pull requests, at least one review from
  the owning team, and a linear history. No direct pushes.
- **Security and compliance sign-off.** Skills in the
  `security-and-compliance` category additionally require review from the
  security team, because they encode regulatory obligations (HIPAA, HITRUST,
  PCI DSS).
- **Versioning and releases.** Tag releases (`v1.3.0`). Applications pin a tag
  rather than tracking the default branch, so agent output stays reproducible
  and upgrades are deliberate.
- **Change log.** Record rule changes in the release notes, with the migration
  impact on already-modernized applications.
- **Review cadence.** Re-validate every skill at least twice a year, and
  immediately when a referenced SDK, Azure service, or regulation changes.
  Deprecate rather than silently delete: mark the skill deprecated in
  `metadata.json`, point to its replacement, and remove it in the next major
  release.
- **Automated checks.** Validate on every pull request that each skill folder
  contains `skill.md` and a `metadata.json` with all required fields, that
  `name` matches the folder, that internal links resolve, and that secret
  scanning finds nothing in `examples/`.
- **Feedback loop.** When a modernization pull request needs a manual
  correction, raise an issue against the skill that produced it. Skills improve
  from real migrations, not from theory.
- **No secrets, no real data, ever.** Enable secret scanning and push protection
  on this repository. Examples use fictional organizations such as Caldova and
  synthetic identifiers only.

## License

See [LICENSE](LICENSE).
