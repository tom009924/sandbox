package com.example.todo.mbt

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class DmnRuleSpec(
    val id: String,
    val description: String,
    val inputs: Map<String, String>,
    val outputs: Map<String, String>,
)

data class DmnDecisionSpec(
    val id: String,
    val name: String,
    val hitPolicy: String,
    val operations: List<String>,
    val rules: List<DmnRuleSpec>,
)

data class DmnModelSpec(
    val id: String,
    val decisions: Map<String, DmnDecisionSpec>,
)

object DmnModelLoader {
    private const val DMN_NS = "https://www.omg.org/spec/DMN/20191111/MODEL/"

    fun load(path: Path): DmnModelSpec {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder().parse(path.toFile())
        val root = document.documentElement
        val decisions = root.childElements(DMN_NS, "decision").associate { decisionEl ->
            val spec = parseDecision(decisionEl)
            spec.id to spec
        }
        return DmnModelSpec(
            id = root.getAttribute("id"),
            decisions = decisions,
        )
    }

    private fun parseDecision(decisionEl: Element): DmnDecisionSpec {
        val operations = decisionEl.getElementsByTagNameNS("*", "operations")
            .let { list -> if (list.length > 0) list.item(0).textContent.trim() else "" }
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)

        val tableEl = decisionEl.childElements(DMN_NS, "decisionTable").first()
        val inputNames = tableEl.childElements(DMN_NS, "input").map { it.getAttribute("label") }
        val outputNames = tableEl.childElements(DMN_NS, "output").map { it.getAttribute("name") }
        val rules = tableEl.childElements(DMN_NS, "rule").map { ruleEl ->
            val desc = ruleEl.childElements(DMN_NS, "description").firstOrNull()?.textContent?.trim().orEmpty()
            val inputValues = ruleEl.childElements(DMN_NS, "inputEntry").map(::extractEntryText)
            val outputValues = ruleEl.childElements(DMN_NS, "outputEntry").map(::extractEntryText)
            DmnRuleSpec(
                id = ruleEl.getAttribute("id"),
                description = desc,
                inputs = inputNames.zip(inputValues).toMap(),
                outputs = outputNames.zip(outputValues).toMap(),
            )
        }
        return DmnDecisionSpec(
            id = decisionEl.getAttribute("id"),
            name = decisionEl.getAttribute("name"),
            hitPolicy = tableEl.getAttribute("hitPolicy"),
            operations = operations,
            rules = rules,
        )
    }

    private fun extractEntryText(entryEl: Element): String =
        entryEl.childElements(DMN_NS, "text").first().textContent.trim().removeSurrounding("\"")

    private fun Element.childElements(namespace: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.namespaceURI == namespace && node.localName == localName) {
                result.add(node)
            }
        }
        return result
    }
}
