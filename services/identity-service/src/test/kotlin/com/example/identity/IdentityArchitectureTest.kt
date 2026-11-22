package com.example.identity

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class IdentityArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.example.identity")

    @Test
    fun `domain does not depend on outer layers or frameworks`() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..application..",
                "..infrastructure..",
                "..presentation..",
                "org.springframework..",
                "io.grpc..",
            )
            .check(classes)
    }

    @Test
    fun `application does not depend on presentation or infrastructure`() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..presentation..",
                "..infrastructure..",
                "org.springframework..",
                "io.grpc..",
            )
            .check(classes)
    }
}
