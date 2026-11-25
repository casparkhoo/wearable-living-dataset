# Generic Processor Contract

Status: **Version 1.0.0 draft for architecture verification**

## Purpose

This contract is the boundary between the living dataset/backend and any derived
processor. A processor may be a signal transformation, handcrafted feature extractor,
pretrained encoder, fitted predictor or a composed pipeline.

The contract is intentionally unaware of PaPaGei, MOMENT or any other named model.
Those implementations are verifier fixtures: they test whether the same stable
interface can support different scientific requirements without changing acquisition
storage.

## Core Rule

> A new conforming processor may add a manifest and adapter, but
> must not require a new acquisition table, overwrite native evidence, or introduce a
> processor-named backend route.

## Boundary of Responsibility

### Living dataset and backend

The stable system is responsible for:

- experiment, device and device-recording identity;
- raw-packet and native-stream persistence;
- channel, unit, source-rate and clock metadata;
- selection of eligible physical evidence intervals;
- snapshot and grouped-split identity;
- request orchestration, state and failure isolation;
- generation of export manifests and checksums; and
- checking a manifest before dispatch.

### Processor adapter

The replaceable adapter is responsible for:

- enforcing its declared input contract;
- applying its ordered, versioned preprocessing;
- returning machine-readable quality decisions;
- producing only declared artifact types;
- reporting its implementation, environment and component identity; and
- never mutating evidence records.

### Research evaluation

The evaluation layer is responsible for:

- defining the task and annotation vocabulary;
- freezing snapshot membership;
- grouping splits by participant or experiment;
- fitting scaling, feature selection and learned heads using training data only; and
- interpreting outputs only within the declared task.

## Contract Files

| File | Responsibility |
|---|---|
| `backend/contracts/processor-manifest.schema.json` | Declares processor capabilities and requirements. |
| `backend/contracts/processor-run-request.schema.json` | Defines a backend-to-adapter run envelope. |
| `backend/contracts/processor-run-result.schema.json` | Defines success, rejection and failure results. |
| `backend/contracts/examples/` | Concrete protocol envelopes without a live worker dependency. |
| `backend/processors/` | Verifier manifests that instantiate the generic schema. |
| `scripts/validate_processor_contract.py` | Dependency-free structural and project-invariant validation. |

## Manifest Structure

### Processor identity

`processor` declares a stable identifier, semantic version, display name, kind and
description. `kind` describes behaviour rather than a particular library:

- `transformer`
- `feature_extractor`
- `encoder`
- `predictor`
- `pipeline`

### Lifecycle

`lifecycle.status` distinguishes a draft contract from an available adapter.
`requirements_status` prevents unresolved scientific assumptions from appearing
verified. A draft requirements set must list its `open_questions`.

For example, the MOMENT manifest remains draft until its exact checkpoint, input
length, normalisation and embedding dimension have been verified. The schema can be
tested before those scientific choices are invented.

### Execution

`execution` names an adapter and protocol but deliberately does not store a deployment
URL. Runtime configuration or a future backend registry maps an adapter identifier to
its local or remote location. This keeps a portable research manifest separate from a
machine-specific address.

### Inputs

Every input declares:

- its role and logical name;
- modality, stream, channel order and unit;
- physical window duration, stride and alignment basis;
- source- and target-rate policies;
- gap handling; and
- dtype and tensor shape.

Different sampling rates are valid. `preserve` retains the native rate;
`exact` declares a required rate; and `processor_defined` marks a draft requirement
that must be resolved before the adapter becomes available. Resampling always creates
a derived artifact. It never changes the native stream.

### Ordered preprocessing

Each step has an identifier, operation vocabulary term, version, parameters and
`fit_scope`. The fit scope makes leakage-sensitive behaviour explicit:

- `none`: no fitted state;
- `per_example`: calculated independently for one example, such as per-window
  normalisation;
- `training_only`: learned only from the training partition; and
- `external_pretrained`: learned before this dataset and identified as an external
  component.

Operations are open vocabulary strings rather than a fixed enumeration. This permits
new filtering or feature algorithms without a contract-schema migration, while the
operation version and parameters retain reproducibility.

### Quality

The manifest declares minimum clock quality, named rules and whether failure rejects
or flags an example. The run result returns the outcome of every evaluated rule rather
than only a free-text error.

### Outputs

Outputs use a small set of scientific artifact categories:

- prepared input;
- feature vector;
- embedding;
- prediction;
- quality report; and
- export.

Each output declares dtype, shape, storage form and semantics. An embedding must say
that it is a representation; it is not silently treated as a physiological
prediction.

### Reproducibility

For thesis verification, all manifests require:

- manifest checksum;
- input checksum;
- output checksum;
- code revision;
- environment identifier; and
- component or checkpoint digest.

These are requirements of a successful reproducible export. The current legacy PaPaGei
route does not yet satisfy the generic envelope; its verifier manifest therefore
remains `draft` even though legacy inference already works.

## Run Request

The backend sends:

- a unique run ID;
- processor ID, version and manifest checksum;
- snapshot and analysis-example context;
- one or more named artifact inputs;
- exact source bounds and clock metadata;
- dtype, shape, channel order, unit and source rate; and
- run-specific parameters.

Payloads may be inline JSON for small arrays or a checksum-protected blob reference.
The source artifact identity remains present either way.

## Run Result

The adapter returns one of:

- `succeeded`: declared outputs were produced;
- `rejected`: evidence failed a declared scientific or quality requirement; or
- `failed`: execution failed for an operational reason.

Every result returns quality rule outcomes and provenance. A successful result must
return its typed outputs. A rejected or failed result should return no scientific
output and should include the optional structured `error` object. These status/output
relationships will be enforced in the Rust implementation in addition to structural
schema validation.

## Verifier Manifests

The current fixtures deliberately exercise different behaviours:

| Verifier | Contract behaviour tested | Current state |
|---|---|---|
| Handcrafted PPG | Native-rate input, named features and interpretable output | Draft; spectral bands remain task-dependent |
| PaPaGei-S | Fixed duration/rate/count, ordered filtering/resampling, 512D embedding | Requirements verified; generic HTTP adapter pending |
| MOMENT-1 | General time-series encoder independent of PaPaGei assumptions | Draft; checkpoint input contract must be verified |

They do not define the supported-model list. A fourth processor is expected to use
the same schema and run envelopes.

## Mechanical Verification

From the repository root:

```powershell
python scripts\validate_processor_contract.py
```

The command checks:

1. all JSON files parse;
2. manifests satisfy the generic JSON Schema;
3. identifiers and ordered step names are unique;
4. exact-rate declarations include the required numeric fields;
5. target rate, duration and sample count agree;
6. draft scientific requirements expose open questions;
7. reproducibility controls are enabled;
8. request/result examples satisfy their schemas and refer to the same run; and
9. the generic manifest schema contains no verifier-specific names.

## Human Review Checklist

Mechanical validity does not make a scientific recipe correct. Before approving
contract version 1, verify:

- Are experiment/session time intervals the stable unit, rather than a model window?
- Can a processor request multiple streams without changing the acquisition schema?
- Are native rates preserved and all rate conversion operations declared?
- Can annotations be reused by different window recipes?
- Does every fitted step state `training_only`?
- Are output semantics clear enough to distinguish an embedding from a prediction?
- Could a new processor be added without adding its name to a schema or endpoint?

## Next Implementation Milestone

After this contract is reviewed, implement one query-time request/result envelope and
export manifest over the existing evidence schema. Route the PaPaGei worker through
that envelope, then add MOMENT without a schema migration. Persistent run/artifact
tables are deliberately deferred unless later evaluation demonstrates a requirement
that cannot be met by source IDs, manifests and checksummed exports.
