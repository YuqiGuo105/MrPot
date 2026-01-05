# Mr Pot

## Swagger UI

Once the application is running, the interactive API documentation is available at:

- Swagger UI: http://localhost:8080/swagger-ui
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## Configuration

- **DashScope (Qwen)**: set an environment variable `DASHSCOPE_API_KEY` so Spring AI can initialize the DashScope client (also used by the Qwen VL tooling). Example for local runs:

  ```bash
  export DASHSCOPE_API_KEY=your-dashscope-api-key
  ./mvnw spring-boot:run
  ```

  In CI, add the secret as `DASHSCOPE_API_KEY` and expose it to Maven or the application process.
