# Development Script Prepare Separation Hygiene

Status: Open hygiene

## Finding

The development server is SBT-free after a separate runtime-classpath refresh,
but the evidence is checked only for presence and the debug server still starts
top-level SBT. The project has no aggregate prepare entry or digest-backed
freshness contract comparable to ArtScene.

## Required direction

- Add one bounded prepare entry for build, CAR, dependency, and runtime
  evidence through the shared serialized SBT runner.
- Make all maintained runtime/debug entry points consume prepared evidence only.
- Reject missing and stale state from explicit input/artifact digests.
- Avoid repeating unchanged preparation in compile-and-restart loops.

## Completion evidence

Prepare succeeds under serialization; runtime paths are statically SBT-free;
current evidence launches; missing or stale evidence fails before runtime
without implicit repair.
