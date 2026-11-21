package io.github.chunyoungsang.moviebot;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.github.chunyoungsang.moviebot.support.MySqlTestContainer;

@SpringBootTest
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class MovieBotApplicationTests {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlTestContainer.registerFreshDatabase(registry);
    }

    @Test
    void contextLoads() {
    }
}