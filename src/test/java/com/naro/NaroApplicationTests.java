package com.naro;

import com.naro.seed.LocalContentSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({
        PostgresTestcontainersConfiguration.class,
        MinioTestcontainersConfiguration.class
})
@Testcontainers(disabledWithoutDocker = true)
class NaroApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    /* Os testes sobem com o perfil "test", nunca com o "local" padrão. */
    @Test
    void localSeedIsNotPartOfTheTestContext() {
        assertThat(context.getBeanNamesForType(LocalContentSeeder.class)).isEmpty();
    }

}
