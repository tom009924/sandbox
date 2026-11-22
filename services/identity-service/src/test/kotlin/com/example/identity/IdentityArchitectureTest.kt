package com.example.identity

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

    @Test
    fun `packages are free of cycles and follow general coding conventions`() {
        slices()
            .matching("com.example.identity.(*)..")
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
