import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import support.MySqlTestContainer;

@SpringBootTest(classes = domain.TestApplication.class)
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