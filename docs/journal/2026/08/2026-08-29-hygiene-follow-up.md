# Supervisor Hygiene Follow-up — 2026-08-29

## HYG-SUPERVISOR-ABI-BASELINE-MISSING

- Status: `OPEN`
- Discovery: 2026-08-29, `cncf-validated-commit` preparation
- Repository: `textus-supervisor`
- Location: `src/main/car/abi-manifest.json`
- Category: release metadata
- Priority: medium
- Evidence: strict CNCF CAR lint completed successfully except for `abi.baseline.missing`; the `0.1.1-SNAPSHOT` CAR identity, current manifest, build wiring, and component contract were otherwise accepted.
- Risk: compatibility with the previously released CAR is not compared against an explicit ABI baseline during lint.
- Outside current boundary: this commit repairs Supervisor process-ownership recovery and aligns the existing development coordinate; selecting or creating a released-CAR ABI baseline is separate release-maintenance work.
- Proposed task: establish the authoritative released-CAR ABI baseline input and rerun strict CAR lint before the next public release.

## HYG-SUPERVISOR-STANDALONE-SOURCE-RESPONSIBILITY

- Status: `OPEN`
- Discovery: 2026-08-29, `cncf-validated-commit` semantic review
- Repository: `textus-supervisor`
- Location: `src/main/scala/org/simplemodeling/textus/supervisor/impl/StandaloneTextusSupervisor.scala`
- Category: source responsibility and reviewability
- Priority: medium
- Evidence: the source is 1,016 lines, exceeding the 1,000-line source-size-debt threshold, and combines development profile/catalog parsing, process hosting and process-tree control, durable state persistence, and lifecycle orchestration.
- Risk: independently changing host and lifecycle responsibilities become increasingly expensive to review together.
- Outside current boundary: a safe extraction must preserve private process-ownership behavior and durable-state compatibility; it is not required to correct the current recovery defect.
- Proposed task: split the provider/process-host, state-store, and lifecycle-orchestration responsibilities under a behavior-preserving maintenance task with the existing focused specification as its acceptance baseline.
