# ECH/DoH isolated JVM regression

This directory contains a standalone regression harness for `EchDoh.kt`, `EchHosts.kt`, and `HDns.kt`. It compiles the repository's production sources directly against local minimal Android/app stubs; it does not modify or build the Android app.

Run from any working directory:

```bash
./tests/ech-doh/run-tests.sh
```

The runner finds the repository root from Git (with a directory fallback), then locates the production sources there. It uses dependency jars from `/workspace/han1me-ech-test/lib` when available; otherwise it downloads the pinned OkHttp 5.4.0, Okio 3.16.0 and DoH artifacts into the system temporary directory. Set `ECH_DOH_REFERENCE_DIR` to use another reference bundle or `KOTLIN_GRADLE_LIB` to select a Gradle compiler library directory.

Captured DNS wire fixtures validate parsing without live endpoint requests. Missing Tencent fixture variants reuse the captured Alibaba response. Concurrent mock counters/recorded values are thread-safe; test assertions are made outside mock callbacks.

Coverage includes routing and fail-closed behavior; primary-to-secondary fixture fallback, cache expiry and invalidation; one-source-per-IP transport/HTTP failures; immediate ECH failure cooldown; concurrent ECH and same-domain DNS singleflight; endpoint ordering, cooldown/preference reuse; single-wait slow failover timing; and ensuring NXDOMAIN on public DNS does not cool endpoints shared with ECH.

This is an isolated JVM regression, not Android instrumentation or a measurement of real network latency. OkHttp calls and failures are mocked. The injected slow endpoint delay tests retry structure only; measured times are local test-harness observations, not expected user connectivity performance.
