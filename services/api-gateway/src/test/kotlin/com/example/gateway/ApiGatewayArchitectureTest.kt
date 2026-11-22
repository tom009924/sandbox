package com.example.gateway

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RestController

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

    @Test
    fun `packages are free of cycles and follow general coding conventions`() {
        slices()
            .matching("com.example.gateway.(*)..")
            .should().beFreeOfCycles()
            .check(classes)

        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes)
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes)

        noFields()
            .should().beAnnotatedWith(Autowired::class.java)
            .check(classes)

        classes()
            .that().areAnnotatedWith(RestController::class.java)
            .and().areNotInterfaces()
            .should().haveSimpleNameEndingWith("Controller")
            .check(classes)
    }
}
