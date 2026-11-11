package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.repository.SpannerRepository
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.util.UUID

@SpringBootTest
class AllRepositoriesDynamicSmokeTest @Autowired constructor(
    private val applicationContext: ApplicationContext,
) {
    @TestFactory
    fun `all declared query methods in all Spanner repositories must be executable`(): List<DynamicNode> {
        val repositoryBeans = applicationContext.getBeansOfType(SpannerRepository::class.java)

        return repositoryBeans.map { (beanName, repositoryInstance) ->
            val repoInterface = repositoryInstance::class.java.interfaces
                .first { SpannerRepository::class.java.isAssignableFrom(it) }

            val queryMethods = repoInterface.declaredMethods.filter { method ->
                !method.isSynthetic && !method.isBridge
            }

            DynamicContainer.dynamicContainer(
                "Repository: ${repoInterface.simpleName} ($beanName)",
                queryMethods.map { method ->
                    DynamicTest.dynamicTest("Query: ${method.name}") {
                        val dummyArgs = method.parameterTypes.map { createDummyValue(it) }.toTypedArray()
                        assertDoesNotThrow {
                            method.invoke(repositoryInstance, *dummyArgs)
                        }
                    }
                },
            )
        }
    }

    private fun createDummyValue(type: Class<*>): Any? = when (type) {
        String::class.java -> UUID.randomUUID().toString()
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> 0L
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 0
        List::class.java -> emptyList<Any>()
        Set::class.java -> emptySet<Any>()
        else -> null
    }
}
