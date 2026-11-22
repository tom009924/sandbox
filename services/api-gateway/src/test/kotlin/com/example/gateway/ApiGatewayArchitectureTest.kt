package com.example.gateway

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class ApiGatewayArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.example.gateway")

    @Test
    fun `grpc and security packages do not depend on presentation`() {
        noClasses()
            .that().resideInAnyPackage("..grpc..", "..security..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .check(classes)
    }

    @Test
    fun `config package does not depend on presentation`() {
        noClasses()
            .that().resideInAPackage("..config..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .check(classes)
    }
}
