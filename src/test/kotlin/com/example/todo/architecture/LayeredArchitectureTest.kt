package com.example.todo.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.example.todo"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class LayeredArchitectureTest {
    @ArchTest
    fun layersFollowDependencyDirection(classes: JavaClasses) {
        val rule: ArchRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Presentation").definedBy("..presentation..")
            .layer("Application").definedBy("..application..")
            .layer("Domain").definedBy("..domain..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .whereLayer("Presentation").mayOnlyAccessLayers("Application", "Domain")
            .whereLayer("Application").mayOnlyAccessLayers("Domain")
            .whereLayer("Domain").mayNotAccessAnyLayer()
            .whereLayer("Infrastructure").mayOnlyAccessLayers("Application", "Domain")

        rule.check(classes)
    }

    @ArchTest
    fun domainAndApplicationDoNotDependOnFrameworks(classes: JavaClasses) {
        noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..")
            .because("Domain and Application layers must remain framework-agnostic")
            .check(classes)
    }
}
