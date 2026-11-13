# Synthetic Issue S203-1: Cost-threshold hint for selected routes

(Synthetic issue written for the #203 review pilot. It is not on GitHub. Treat it as the issue under review.)

## Background

Forge hosts show a confirmation prompt before running an API-metered route. Hosts asked for a way to tell, from a
routing decision, whether the selected route's known cost estimate falls within a threshold the host chooses, so the
host can decide whether to prompt.

## Goal

Add a small helper in `monada.neuron.routing` that, given a `RoutingDecision.Selected`, the `RouteCatalog` it was made
from, and a caller-supplied threshold in one resource dimension and unit, reports whether the selected route's known
estimate is within the threshold.

## Non-goals

- No change to eligibility, ranking, or decision records.
- No change to the Forge routing contract or to ADR 0026.

## Architecture boundaries

Follow ADR 0026 (Forge routing ownership) and the Forge Routing Contract v1.

## Acceptance criteria

- AC1: For a selected route with a `Known` estimate in the requested dimension and unit, the helper reports whether the
  value is within the inclusive threshold.
- AC2: A missing estimate, an `Unknown` or `NotMeasured` value, or an estimate in another unit is reported as not within
  the threshold.
- AC3: Tests cover AC1 and AC2, including the boundary value.
- AC4: `./gradlew test` passes.
