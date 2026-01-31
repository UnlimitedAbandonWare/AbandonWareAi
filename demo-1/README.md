# demo-1

Run with:

```bash
./gradlew :demo-1:bootRun
```

Requires Java 17. Spring Boot 3, LangChain4j 1.0.1.

Key endpoints:
- `GET /actuator/health` - health check
- `POST /api/probe/search` - probe search (admin-token required)