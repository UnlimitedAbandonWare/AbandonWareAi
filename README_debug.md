# Debugging and Reproduction Guide

This document explains how to build and run the patched agent locally and
demonstrates how to exercise the new `/flows/{flow}:run` endpoint with trace
enabled.

## Build Instructions

1. **Install JDK 17** – Ensure `java` on your `PATH` points to a Java 17
   runtime.  Spring Boot 3.x requires a recent JVM.

2. **Generate a jar** – From the project root, run the following command:

   ```sh
   ./gradlew clean check bootJar
   ```

   The `check` phase will also execute the placeholder guard and unit tests.

3. **Start the application** – The built jar will reside under
   `app/build/libs/`.  To start the service, run:

   ```sh
   java -jar app/build/libs/app-*.jar
   ```

   By default the server listens on port 8080.

## Executing a Flow

Flows can be executed over HTTP using the new debug endpoint.  The general
format is:

```
POST http://localhost:8080/flows/{flowName}:run
Content-Type: application/json
?trace=on|off

{ ...input payload... }
```

### Example: `kakao/ask` Flow

Send a message asking the assistant a question.  Replace `roomId` with a
valid Kakao room identifier if you have one.  Enabling trace collects
detailed step and tool invocation information.

```sh
curl -sS -X POST \
  'http://localhost:8080/flows/kakao_ask:run?trace=on' \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"r-123","text":"안녕?"}' | jq .
```

The response will include the final flow state and, when `trace=on`, a
`trace` field containing an array of executed steps and tool calls with
their inputs, outputs and durations.  Trace entries are also appended to
`logs/trace.ndjson` in NDJSON format for offline analysis.

## Checking Metrics

Micrometer metrics are exposed via Spring Boot Actuator on `/actuator/metrics`.
The following command lists all available metrics:

```sh
curl -sS http://localhost:8080/actuator/metrics | jq .
```

Specifically, the agent publishes the following counters and timers:

- `agent_steps_total{step}` – counts how many times each step type ran.
- `agent_tool_invocations_total{tool}` – counts successful tool invocations.
- `agent_tool_errors_total{tool}` – counts tool executions that threw exceptions.
- `latency_ms{operation}` – measures duration of steps and tool calls.

## Notes

- To hot‑reload flow definitions during development, set the environment
  variable `AGENT_FLOWS_PATH` to the directory containing your YAML files
  before starting the application.  Edited files will be picked up on the
  next invocation without restarting the service.
- Additional actuator endpoints such as `loggers`, `threaddump` and
  `httpexchanges` have been enabled for troubleshooting.

> Note: If `./gradlew` is a placeholder in your environment, use a local Gradle installation:

```
gradle clean check bootJar
```

Or run with Java directly after compiling.


## Gradient Vanishing Diagnostics (New)

When enabled, the agent exposes a diagnostic endpoint that accepts per-layer gradient L2 norms and returns a **sigmoid‑calibrated** vanishing risk in [0,1].

### Enable

Set an environment variable or application property:

```bash
export DIAG_NN_GRAD_ENABLED=true
# optional tuning
export DIAG_NN_GRAD_VANISH_THRESHOLD=0.001
export DIAG_NN_GRAD_ALPHA=4.0
export DIAG_NN_GRAD_BETA=0.0
export DIAG_NN_GRAD_EPS=1e-12
```

### POST sample

```bash
curl -sS http://localhost:8080/diag/nn/gradients \
  -H 'Content-Type: application/json' \
  -d '{
        "model":"my_onnx_reranker",
        "layers":[
          {"layer":"embeddings", "norm": 2.5e-3},
          {"layer":"encoder_0",  "norm": 7.1e-4},
          {"layer":"encoder_1",  "norm": 8.9e-5}
        ]
      }' | jq .
```

Response:

```json
{
  "model": "my_onnx_reranker",
  "layers": [
    {"layer":"embeddings","norm":0.0025,"vanishProb":0.0731,"flag":false},
    {"layer":"encoder_0","norm":0.00071,"vanishProb":0.5663,"flag":true},
    {"layer":"encoder_1","norm":0.000089,"vanishProb":0.9248,"flag":true}
  ],
  "meanVanishProb": 0.5214
}
```

Metrics published (Micrometer):
- `agent.nn.grad.vanish_prob{model,layer}`
- `agent.nn.grad.l2norm{model,layer}`
- `agent.nn.grad.vanish_prob_mean{model}`

