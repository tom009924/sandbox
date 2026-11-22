package com.example.todo.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.library.GeneralCodingRules
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RestController

@AnalyzeClasses(
    packages = ["com.example.todo"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureConventionsTest {

    @ArchTest
    fun packagesShouldBeFreeOfCycles(classes: JavaClasses) {
        slices()
            .matching("com.example.todo.(*)..")
            .should().beFreeOfCycles()
            .because("Circular package dependencies must be avoided")
            .check(classes)
    }

    @ArchTest
    fun noAccessToStandardStreams(classes: JavaClasses) {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("Standard output/error streams should not be used; use SLF4J logger instead")
            .check(classes)
    }

    @ArchTest
    fun noUseOfJavaUtilLogging(classes: JavaClasses) {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
            .because("SLF4J should be used instead of java.util.logging")
            .check(classes)
    }

    @ArchTest
    fun domainAndApplicationShouldNotThrowGenericExceptions(classes: JavaClasses) {
        noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should(GeneralCodingRules.THROW_GENERIC_EXCEPTIONS)
            .because("Domain and Application layers should throw specific domain/application exceptions")
            .check(classes)
    }

    @ArchTest
    fun noFieldInjection(classes: JavaClasses) {
        noFields()
            .should().beAnnotatedWith(Autowired::class.java)
            .because("Constructor injection should be used instead of field injection")
            .check(classes)
    }

    @ArchTest
    fun controllersShouldFollowNamingConvention(classes: JavaClasses) {
        classes()
            .that().areAnnotatedWith(RestController::class.java)
            .and().areNotInterfaces()
            .should().haveSimpleNameEndingWith("Controller")
            .because("Concrete REST controller implementations should be suffixed with 'Controller'")
            .check(classes)
    }
}
