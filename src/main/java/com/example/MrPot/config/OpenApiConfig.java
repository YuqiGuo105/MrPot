package com.example.MrPot.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "MrPot API",
                version = "v1",
                description = "Swagger UI documentation for the MrPot service"
        )
)
public class OpenApiConfig {
}
