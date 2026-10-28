package com.travelpay.exchange;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * 4계층 의존 방향을 테스트로 고정합니다.
 * presentation → application → domain 순으로만 의존하고, infrastructure는 application·domain의 인터페이스를 구현합니다.
 */
@AnalyzeClasses(packages = "com.travelpay.exchange", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layerDependencies = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Presentation").definedBy("com.travelpay.exchange.presentation..")
            .layer("Application").definedBy("com.travelpay.exchange.application..")
            .layer("Domain").definedBy("com.travelpay.exchange.domain..")
            .layer("Infrastructure").definedBy("com.travelpay.exchange.infrastructure..")
            .whereLayer("Presentation").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Presentation", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Presentation", "Application", "Infrastructure")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer();

    /** 도메인에는 JPA 어노테이션만 허용하고 Spring에는 의존하지 않습니다. */
    @ArchTest
    static final ArchRule domainDoesNotDependOnSpring = noClasses()
            .that().resideInAPackage("com.travelpay.exchange.domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");
}
