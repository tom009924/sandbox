package com.example.identity.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersistenceSliceTestConventionsTest {

    @Test
    fun allRepositoryAdaptersMustHaveCorrespondingTestClass() {
        val classes = ClassFileImporter().importPackages("com.example.identity")
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
