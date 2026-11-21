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
 *   domain  비즈니스 도메인 (movie, notification, user). 외부 기술을 모르고, 필요한 기능은 인터페이스(포트)로 정의한다.
 *   infra   외부 연동 (telegram, cgv). domain 의 포트를 구현하거나 domain 서비스를 호출한다.
 *   global  공통 설정·예외. domain 과 infra 를 모른다.
 *
 * 의존 방향: infra → domain, (domain, infra) → global
 */
@DisplayName("아키텍처 규칙")
class ArchitectureTest {

    private static final String BASE = "io.github.chunyoungsang.moviebot";
    private static final String DOMAIN = BASE + ".domain";
    private static final String INFRA = BASE + ".infra";
    private static final String GLOBAL = BASE + ".global";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    @DisplayName("[순환 금지] 도메인끼리 서로 참조하지 않는다")
    void domainsAreFreeOfCycles() {
        check("도메인 간 순환 의존", slices().matching(DOMAIN + ".(*)..").should().beFreeOfCycles());
    }

    @Test
    @DisplayName("[의존 방향] domain 은 infra(외부 연동)를 알지 못한다")
    void domainDoesNotDependOnInfra() {
        check("domain → infra 의존", noClasses()
                .that().resideInAPackage(DOMAIN + "..")
                .should().dependOnClassesThat().resideInAPackage(INFRA + ".."));
    }

    @Test
    @DisplayName("[의존 방향] domain 은 외부 연동 기술(Playwright, RestTemplate)을 직접 쓰지 않는다")
    void domainDoesNotUseExternalClients() {
        check("domain → 외부 연동 기술 의존", noClasses()
                .that().resideInAPackage(DOMAIN + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.microsoft.playwright..", "org.springframework.web.client.."));
    }

    @Test
    @DisplayName("[독립] infra 모듈(telegram, cgv)끼리 서로 참조하지 않는다")
    void infraModulesAreIndependent() {
        check("infra 모듈 간 의존", slices().matching(INFRA + ".(*)..").should().notDependOnEachOther());
    }

    @Test
    @DisplayName("[공통 패키지] global 은 domain, infra 에 의존하지 않는다 (설정·공통 예외만 둔다)")
    void globalDoesNotDependOnDomainOrInfra() {
        check("global → domain/infra 의존", noClasses()
                .that().resideInAPackage(GLOBAL + "..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAIN + "..", INFRA + ".."));
    }

    @Test
    @DisplayName("[구조] 최상위에는 domain, infra, global 과 애플리케이션 클래스만 둔다")
    void onlyKnownTopLevelPackages() {
        check("최상위 패키지 규칙", com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                .that().resideInAPackage(BASE + "..")
                .should().resideInAnyPackage(BASE, DOMAIN + "..", INFRA + "..", GLOBAL + ".."));
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
