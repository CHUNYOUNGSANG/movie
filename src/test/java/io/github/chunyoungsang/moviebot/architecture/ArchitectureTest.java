package io.github.chunyoungsang.moviebot.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패키지 의존 규칙.
 *
 * 의존 방향: telegram(외부 연동) → notification → movie, user
 *            global(공통 설정)은 어떤 도메인에도 의존하지 않는다.
 */
@DisplayName("아키텍처 규칙")
class ArchitectureTest {

    private static final String BASE = "io.github.chunyoungsang.moviebot";
    /** 도메인 패키지들의 부모 (예: BASE.movie, BASE.notification) */
    private static final String DOMAINS = BASE;
    private static final String GLOBAL = BASE + ".global";
    private static final String[] ALL_DOMAINS = {
            DOMAINS + ".movie..", DOMAINS + ".notification..", DOMAINS + ".user..", DOMAINS + ".telegram.."};

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    @DisplayName("[순환 금지] 도메인 패키지끼리 서로 참조하지 않는다")
    void domainsAreFreeOfCycles() {
        check("도메인 간 순환 의존", slices().matching(DOMAINS + ".(*)..").should().beFreeOfCycles());
    }

    @Test
    @DisplayName("[의존 방향] 업무 도메인(movie, notification, user)은 외부 연동(telegram)을 알지 못한다")
    void businessDomainsDoNotDependOnTelegram() {
        check("업무 도메인 → telegram 의존", noClasses()
                .that().resideInAnyPackage(DOMAINS + ".movie..", DOMAINS + ".notification..", DOMAINS + ".user..")
                .should().dependOnClassesThat().resideInAPackage(DOMAINS + ".telegram.."));
    }

    @Test
    @DisplayName("[공통 패키지] global 은 도메인에 의존하지 않는다 (설정·공통 예외만 둔다)")
    void globalDoesNotDependOnDomains() {
        check("global → 도메인 의존", noClasses()
                .that().resideInAPackage(GLOBAL + "..")
                .should().dependOnClassesThat().resideInAnyPackage(ALL_DOMAINS));
    }

    /** 위반 건수를 출력하고 0 이어야 통과 */
    private static void check(String name, ArchRule rule) {
        EvaluationResult result = rule.allowEmptyShould(true).evaluate(CLASSES);
        var violations = result.getFailureReport().getDetails();
        System.out.printf("[아키텍처] %s: 위반 %d건%n", name, violations.size());
        violations.stream().limit(10).forEach(v -> System.out.println("  - " + v));
        assertThat(violations).as(name).isEmpty();
    }
}
