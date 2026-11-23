package com.morph.app.engine

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.morph.app.domain.model.*
import com.morph.app.domain.model.Format.*
import java.io.File
import java.util.Properties
import javax.xml.stream.XMLInputFactory

object DataEngine {
    private val json = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .also { it.factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).build()) }
    private val xml = XmlMapper(com.fasterxml.aalto.stax.InputFactoryImpl().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    })
    fun read(src: File,fmt: Format): JsonNode {
        require(src.length() <= 12L shl 20) { "Structured document exceeds the editing memory limit" }
        return when(fmt) {
            JSON -> json.readTree(src)
            YAML -> YAMLMapper().readTree(src)
            XML -> xml.readTree(src)
            PROPERTIES -> Properties().apply { src.reader().use(::load) }.let { json.valueToTree(it) }
            TOML,INI -> {
                val root=json.createObjectNode()
                var section=root
                src.forEachLine { line ->
                    val s=line.trim()
                    when {
                        s.isBlank() || s.startsWith('#') || s.startsWith(';') -> Unit
                        s.startsWith('[') && s.endsWith(']') -> section=root.putObject(s.substring(1,s.lastIndex))
                        else -> {
                            require('=' in s) { "Expected a key and value" }
                            val k=s.substringBefore('=').trim(); val v=s.substringAfter('=').trim()
                            section.set<JsonNode>(k,if(fmt==TOML) runCatching { json.readTree(v) }.getOrNull() ?: json.nodeFactory.textNode(v.trim('\'')) else json.nodeFactory.textNode(v))
                        }
                    }
                }; root
            }
            else -> error("Unknown structured format")
        }
    }
    fun write(node: JsonNode,dst: File,fmt: Format,opt: ProOptions) {
        when(fmt) {
            JSON -> json.writeValue(dst,node)
            YAML -> YAMLMapper().writeValue(dst,node)
            XML -> xml.writer().withRootName("data").writeValue(dst,node)
            PROPERTIES,TOML,INI -> {
                require(node.isObject) { "A key/value object is required for this format" }
                dst.bufferedWriter().use { w ->
                    fun fields(obj: JsonNode) { obj.properties().forEach { (k,v) ->
                        require(!v.isContainerNode && '\n' !in k && '=' !in k) { "Nested value cannot be exported as a scalar" }
                        w.appendLine("$k = " + if(fmt==TOML) v.toString() else v.asText().replace("\n","\\n"))
                    } }
                    val flat=json.createObjectNode()
                    node.properties().forEach { (k,v) -> if(!v.isObject) flat.set<JsonNode>(k,v) }; fields(flat)
                    node.properties().forEach { (k,v) -> if(v.isObject) { require(fmt!=PROPERTIES); w.appendLine("[$k]"); fields(v) } }
                }
            }
            else -> {
                val records=if(node.isArray) node.toList() else listOf(node)
                require(records.all { it.isObject }) { "A table requires rows of key/value pairs" }
                val keys=records.flatMap { it.fieldNames().asSequence().toList() }.distinct()
                if(fmt==SQL) {
                    fun ident(s:String)="\""+s.replace("\"","\"\"")+"\""
                    dst.bufferedWriter().use { w ->
                        require(keys.isNotEmpty())
                        w.appendLine("CREATE TABLE data ("+keys.joinToString { ident(it)+" TEXT" }+");")
                        records.forEach { row -> w.appendLine("INSERT INTO data VALUES ("+keys.joinToString { k -> row.get(k)?.takeUnless { it.isNull }?.let { "'"+it.asText().replace("'","''")+"'" } ?: "NULL" }+");") }
                    }
                } else SpreadsheetEngine.export(listOf(SpreadsheetEngine.Table("Data",listOf(keys)+records.map { r -> keys.map { r[it]?.let { v -> if(v.isContainerNode) v.toString() else v.asText() }.orEmpty() } })),dst,fmt,opt)
            }
        }
    }
}
