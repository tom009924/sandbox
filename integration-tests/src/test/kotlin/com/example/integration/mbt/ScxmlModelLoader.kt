package com.example.integration.mbt

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ScxmlTransition(
    val source: String,
    val event: String,
    val cond: String?,
    val target: String,
)

data class ScxmlModel(
    val name: String,
    val initial: String,
    val states: Set<String>,
    val transitionsByState: Map<String, List<ScxmlTransition>>,
) {
    fun transitionsFrom(state: String): List<ScxmlTransition> = transitionsByState[state].orEmpty()

    fun allEvents(): Set<String> = transitionsByState.values.flatten().mapTo(mutableSetOf()) { it.event }
}

object ScxmlModelLoader {
    private const val SCXML_NS = "http://www.w3.org/2005/07/scxml"

    fun load(path: String): ScxmlModel {
        val file = File(path)
        require(file.exists()) { "SCXML model file not found: $path" }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(file)
        val root = document.documentElement
        require(root.localName == "scxml" && root.namespaceURI == SCXML_NS) {
            "Expected W3C SCXML root element in $path"
        }

        val name = root.getAttribute("name").ifBlank { file.nameWithoutExtension }
        val initial = root.getAttribute("initial")
        require(initial.isNotBlank()) { "SCXML root element must specify initial state" }

        val states = linkedSetOf<String>()
        val transitionsByState = linkedMapOf<String, MutableList<ScxmlTransition>>()

        childElements(root)
            .filter { it.localName == "state" || it.localName == "final" }
            .forEach { elem ->
                val stateId = elem.getAttribute("id")
                require(stateId.isNotBlank()) { "SCXML <${elem.localName}> missing id attribute" }
                states.add(stateId)
                val list = transitionsByState.getOrPut(stateId) { mutableListOf() }
                collectTransitions(elem, stateId, list)
            }

        require(initial in states) { "Initial state '$initial' is not declared in $path" }
        return ScxmlModel(
            name = name,
            initial = initial,
            states = states,
            transitionsByState = transitionsByState,
        )
    }

    private fun collectTransitions(
        stateElement: Element,
        sourceState: String,
        targetList: MutableList<ScxmlTransition>,
    ) {
        childElements(stateElement)
            .filter { it.localName == "transition" }
            .forEach { elem ->
                val event = elem.getAttribute("event")
                require(event.isNotBlank()) { "Transition in state '$sourceState' missing event attribute" }
                val cond = elem.getAttribute("cond").takeIf { it.isNotBlank() }
                val target = elem.getAttribute("target").ifBlank { sourceState }
                targetList.add(
                    ScxmlTransition(
                        source = sourceState,
                        event = event,
                        cond = cond,
                        target = target,
                    ),
                )
            }
    }

    private fun childElements(parent: Element): List<Element> {
        val children = parent.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }
    }
}
