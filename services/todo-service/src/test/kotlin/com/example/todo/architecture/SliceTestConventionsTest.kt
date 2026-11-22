package com.example.todo.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.web.bind.annotation.RestController

@AnalyzeClasses(packages = ["com.example.todo"])
class SliceTestConventionsTest {

    @ArchTest
    fun allRestControllersMustHaveCorrespondingTestClass(classes: JavaClasses) {
        val controllers = classes.filter {
            it.isAnnotatedWith(RestController::class.java) && !it.isInterface
        }
        val allClassNames = classes.map { it.name }.toSet()

        val missingTests = controllers.filter { controller ->
            val expectedTestSimpleName = "${controller.simpleName}Test"
            allClassNames.none { it.endsWith(".$expectedTestSimpleName") || it == expectedTestSimpleName }
        }

        assertTrue(
            missingTests.isEmpty(),
            "以下のRestControllerに対するスライステストクラスが存在しません:\n" +
                missingTests.joinToString("\n") { " - ${it.simpleName} -> 必要: ${it.simpleName}Test" },
        )
    }

    @ArchTest
    fun allRepositoryAdaptersMustHaveCorrespondingTestClass(classes: JavaClasses) {
        val adapters = classes.filter {
            it.packageName.contains("infrastructure.persistence") &&
                it.simpleName.endsWith("RepositoryAdapter") &&
                !it.isInterface
        }
        val allClassNames = classes.map { it.name }.toSet()

        val missingTests = adapters.filter { adapter ->
            val expectedTestSimpleName = "${adapter.simpleName}Test"
            allClassNames.none { it.endsWith(".$expectedTestSimpleName") || it == expectedTestSimpleName }
        }

        assertTrue(
            missingTests.isEmpty(),
            "以下のRepositoryAdapterに対するスライステストクラスが存在しません:\n" +
                missingTests.joinToString("\n") { " - ${it.simpleName} -> 必要: ${it.simpleName}Test" },
        )
    }

    @ArchTest
    fun allSpringDataRepositoriesMustHaveCorrespondingAdapter(classes: JavaClasses) {
        val springDataRepos = classes.filter {
            it.packageName.contains("infrastructure.persistence") &&
                it.simpleName.startsWith("SpringData") &&
                it.isInterface
        }
        val allClassNames = classes.map { it.name }.toSet()

        val missingAdapters = springDataRepos.filter { repo ->
            val domainName = repo.simpleName.removePrefix("SpringData").removeSuffix("Repository")
            val expectedAdapter = "com.example.todo.infrastructure.persistence.${domainName}RepositoryAdapter"
            expectedAdapter !in allClassNames
        }

        assertTrue(
            missingAdapters.isEmpty(),
            "以下のSpringDataRepositoryに対応するRepositoryAdapterが存在しません:\n" +
                missingAdapters.joinToString("\n") { " - ${it.simpleName}" },
        )
    }
}
