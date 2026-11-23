package com.example.todo.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import org.junit.jupiter.api.Assertions.assertTrue

@AnalyzeClasses(packages = ["com.example.todo"])
class SliceTestConventionsTest {

    @ArchTest
    fun allGrpcServicesMustHaveCorrespondingTestClass(classes: JavaClasses) {
        val grpcServices = classes.filter {
            it.packageName.contains("infrastructure.grpc") &&
                it.simpleName.endsWith("GrpcService") &&
                !it.isInterface
        }
        val allClassNames = classes.map { it.name }.toSet()

        val missingTests = grpcServices.filter { service ->
            val expectedTestSimpleName = "${service.simpleName}Test"
            allClassNames.none { it.endsWith(".$expectedTestSimpleName") || it == expectedTestSimpleName }
        }

        assertTrue(
            missingTests.isEmpty(),
            "以下のGrpcServiceに対するスライステストクラスが存在しません:\n" +
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
