# Lessons

## Benchmark Equivalent Test Scopes Before Claiming Performance Impact

- Correction: the user pointed out that the previous timing comparison mixed the default suite with the opt-in `Stress` subset, so it did not answer the actual before/after performance question.
- Rule: when evaluating test-performance impact, compare equivalent test scopes before and after the change.
- Rule: if tag routing changes during the phase, explicitly define how "full corpus" is measured on both revisions before collecting timings.
- Rule: treat verification timings and benchmark timings as different artifacts; rerun benchmark commands from a clean or forced-rerun state before drawing performance conclusions.
