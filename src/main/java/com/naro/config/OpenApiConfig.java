package com.naro.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.PagedResourcesAssembler;

/**
 * Toda a configuração do Swagger vive aqui, então os controllers
 * só carregam @Tag e @Operation.
 *
 * O botão Authorize oferece dois caminhos, ambos valendo para
 * todos os endpoints:
 *
 *   keycloak     login no Keycloak (Authorization Code + PKCE)
 *   bearer-jwt   colar um access token já emitido
 *
 * UI:        /swagger-ui.html
 * Contrato:  /v3/api-docs
 */
@Configuration
@OpenAPIDefinition(

        info = @Info(
                title = "Naro API",
                version = "v1",
                description = """
                        Backend da rede social Naro.

                        **Autenticação:** clique em *Authorize* e entre pelo Keycloak,
                        ou cole um access token em `bearer-jwt`.

                        **Canal:** publicar, comentar e curtir exigem o header
                        `X-Channel-Id` com um canal do usuário autenticado.

                        **HATEOAS:** canais, publicações, comentários, likes, conversas
                        e usuários vêm em HAL, mesmo com `Accept: application/json`.
                        `_links` diz para onde dá para ir a partir do recurso; listas e
                        páginas ficam em `_embedded` (ausente quando vazias), com a
                        navegação (`first`, `prev`, `next`, `last`) em `_links`.

                        **Erros:** Problem Details (RFC 9457).
                        """
        ),

        security = {
                @SecurityRequirement(name = "keycloak"),
                @SecurityRequirement(name = "bearer-jwt")
        }
)
@SecurityScheme(
        name = "keycloak",
        type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(
                authorizationCode = @OAuthFlow(
                        authorizationUrl = "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/protocol/openid-connect/auth",
                        tokenUrl = "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/protocol/openid-connect/token"
                )
        )
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    private static final String CHANNEL_HEADER = "X-Channel-Id";


    /*
     * Os endpoints paginados recebem o PagedResourcesAssembler como
     * parâmetro, como na documentação do Spring Data. Quem o fornece
     * é o Spring, não o cliente, então ele não entra no contrato.
     */
    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(PagedResourcesAssembler.class);
    }


    /*
     * X-Channel-Id aparece em quase todos os controllers.
     * A explicação fica aqui uma vez, em vez de um
     * @Parameter repetido em cada endpoint.
     */
    @Bean
    OperationCustomizer channelHeaderDescription() {

        return (operation, _) -> {

            if (operation.getParameters() != null) {

                operation.getParameters()
                        .stream()
                        .filter(parameter -> CHANNEL_HEADER.equals(parameter.getName()))
                        .forEach(parameter -> parameter.setDescription(
                                "Canal do usuário autenticado que assina a ação. "
                                        + "Nas leituras é opcional e só define `likedByCurrentChannel`."
                        ));
            }

            return operation;
        };
    }
}
