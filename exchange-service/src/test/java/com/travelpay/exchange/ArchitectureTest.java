package com.travelpay.exchange;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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

    /**
     * 도메인에는 JPA 어노테이션만 허용하고 Spring에는 의존하지 않습니다.
     * 예외로 생성·수정 시각 표시 어노테이션(@CreatedDate, @LastModifiedDate)만 허용하고,
     * 이를 채우는 감사 리스너는 META-INF/orm.xml에서 등록합니다.
     */
    @ArchTest
    static final ArchRule domainDoesNotDependOnSpring = noClasses()
            .that().resideInAPackage("com.travelpay.exchange.domain..")
            .should().dependOnClassesThat(resideInAPackage("org.springframework..")
                    .and(not(resideInAPackage("org.springframework.data.annotation"))));
}
