package com.example.todo.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods

@AnalyzeClasses(
    packages = ["com.example.todo"],
    // テストコードを除外する（テストコードからのアクセスはノーカウント）
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class DeadCodeDetectionTest {

    @ArchTest
    fun applicationServicePublicMethodsShouldBeCalledInProduction(classes: JavaClasses) {
        val rule = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..application..")
            .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Service")
            .and().arePublic()
            .and().doNotHaveName("equals")
            .and().doNotHaveName("hashCode")
            .and().doNotHaveName("toString")
            .should(object : ArchCondition<JavaMethod>("be called by production code or interface") {
                override fun check(item: JavaMethod, events: ConditionEvents) {
                    if (item.modifiers.contains(JavaModifier.SYNTHETIC)) return
                    if (item.name.contains("$")) return

                    // 直接の呼び出し元（自身による再帰呼び出しを除く）
                    val directCalls = item.accessesToSelf
                        .filter { it.origin != item }

                    // インターフェース経由の呼び出し（DIでインターフェースに依存している場合）
                    val interfaceCalls = item.owner.allRawInterfaces.flatMap { iface ->
                        iface.methods.filter {
                            it.name == item.name && it.rawParameterTypes == item.rawParameterTypes
                        }.flatMap { it.accessesToSelf }
                    }.filter { it.origin != item }

                    val totalCalls = directCalls + interfaceCalls

                    if (totalCalls.isEmpty()) {
                        val message = "${item.owner.simpleName}#${item.name} is never called in production code"
                        events.add(SimpleConditionEvent.violated(item, message))
                    }
                }
            })

        rule.check(classes)
    }
}
