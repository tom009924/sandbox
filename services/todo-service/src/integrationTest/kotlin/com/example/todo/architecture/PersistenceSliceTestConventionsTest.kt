package com.example.todo.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import org.junit.jupiter.api.Assertions.assertTrue

@AnalyzeClasses(packages = ["com.example.todo"])
class PersistenceSliceTestConventionsTest {

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
}
