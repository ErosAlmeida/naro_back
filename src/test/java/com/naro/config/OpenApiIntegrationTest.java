package com.naro.config;

import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O contrato OpenAPI gerado de verdade, com o contexto inteiro:
 * público, com login pelo Keycloak e com os schemas HAL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        PostgresTestcontainersConfiguration.class,
        MinioTestcontainersConfiguration.class
})
@Testcontainers(disabledWithoutDocker = true)
class OpenApiIntegrationTest {

    private static final String PUBLICATIONS = "$.paths['/api/v1/publications']";

    @Autowired
    private MockMvc mockMvc;


    @Test
    void swaggerUiIsPublic() throws Exception {

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }


    @Test
    void contractIsPublicAndLogsInThroughKeycloak() throws Exception {

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Naro API"))
                .andExpect(jsonPath("$.security[0].keycloak").exists())
                .andExpect(jsonPath("$.security[1].bearer-jwt").exists())
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.authorizationUrl")
                        .value(endsWith("/realms/social/protocol/openid-connect/auth")))
                .andExpect(jsonPath("$.components.securitySchemes.keycloak.flows.authorizationCode.tokenUrl")
                        .value(endsWith("/realms/social/protocol/openid-connect/token")));
    }


    @Test
    void contractDescribesTheHalResponses() throws Exception {

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PUBLICATIONS + ".get.responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/PagedModelEntityModelPublicationResponse"))
                .andExpect(jsonPath("$.components.schemas.PagedModelEntityModelPublicationResponse.properties._embedded.properties.publications.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.PagedModelEntityModelPublicationResponse.properties.page").exists())
                .andExpect(jsonPath("$.components.schemas.EntityModelPublicationResponse.properties._links").exists());
    }


    @Test
    void contractHasTheRealStatusCodesAndHeaders() throws Exception {

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PUBLICATIONS + ".post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/publications/{publicationId}/comments/{commentId}'].delete.responses['204']").exists())
                // X-Channel-Id é descrito uma vez no OpenApiConfig.
                .andExpect(jsonPath(PUBLICATIONS + ".post.parameters[?(@.name == 'X-Channel-Id')].description").isNotEmpty())
                // @CurrentUser vem do token, não é parâmetro da API.
                .andExpect(jsonPath("$.paths['/api/users/me'].get.parameters").doesNotExist())
                // O PagedResourcesAssembler vem do Spring, não do cliente.
                .andExpect(jsonPath(PUBLICATIONS + ".get.parameters[?(@.name == 'pagedAssembler')]").isEmpty())
                .andExpect(jsonPath("$.components.schemas.PagedResourcesAssemblerPublicationResponse").doesNotExist());
    }
}
