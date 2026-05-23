package kr.go.smes.support.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "OnePass Support API",
                version = "v1",
                description = "OnePass Q&A/FAQ 지원 API",
                contact = @Contact(name = "OnePass Platform Team")
        )
)
@SecurityScheme(
        name = "supportUser",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-User-Id"
)
@SecurityScheme(
        name = "supportUserRole",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-User-Role"
)
public class OpenApiConfig {

    @Bean
    public OpenAPI supportOpenApi() {
        return new OpenAPI().addServersItem(new Server().url("/"));
    }
}

