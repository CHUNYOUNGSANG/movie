import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(classes = domain.TestApplication.class)
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class MovieBotApplicationTests {

    @Test
    void contextLoads() {
    }
}