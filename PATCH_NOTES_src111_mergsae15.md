# PATCH NOTES — src111_mergsae15

Date (UTC): 2025-10-16T11:46:54.538787Z

This patch applies the Hyper‑Nova fusion refinements and repairs common build error patterns detected in prior logs.

## What changed

1) **Hyper‑Nova components (app module)**
   - `app/src/main/java/com/nova/protocol/fusion/CvarAggregator.java`
     * CVaR@α upper‑tail average kept; mixing formula stabilized with smooth clamp via Bode‑like limiter.
   - `app/src/main/java/com/nova/protocol/fusion/TailWeightedPowerMeanFuser.java`
     * Dynamic power `p` derived from tail index = CVaR/mean (top‑tail) to emphasize strong signals while remaining bounded.
   - (No signature changes — binary compatible with `NovaNextFusionService`.)

2) **Bridge remains the same**
   - `RrfHypernovaBridge` + `NovaNextConfig` gate Hyper‑Nova via `spring.profiles.active=novanext` or `nova.next.enabled=true`.

3) **Build error pattern fixes (IllegalStartOfType / ClassOrInterfaceExpected)**
   - Rewrote *minimal, compilable* stubs for duplicated legacy `WeightedRRF` classes that contained stray `...` tokens:
     * `src/main/java/com/abandonware/ai/service/rag/fusion/WeightedRRF.java`
     * `src/main/java/service/rag/fusion/WeightedRRF.java`
     * `src/main/java/com/example/rag/fusion/WeightedRRF.java`
   - The app module does **not** compile the root `src/main/java/**` tree, but normalizing them removes noise for IDEs and future Gradle include refactors.

## How to enable Hyper‑Nova (already present)

- Use the provided profile file: `app/src/main/resources/application-nova-next.yml`
  ```
  spring:
    config.activate.on-profile: novanext
  nova.next.enabled: true
  whitening.enabled: true
  ```
  Then run: `SPRING_PROFILES_ACTIVE=novanext` (or map the properties in your deployment).

## Safety

- Power‑mean output and CVaR mix are hard‑clamped to [0,1] to prevent score explosions.
- All new code is dependency‑free Java 17 and does not alter public APIs used by other modules.

