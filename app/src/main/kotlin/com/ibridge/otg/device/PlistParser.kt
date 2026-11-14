package com.ibridge.otg.device

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Minimal plist XML reader for the flat array-of-dictionaries output produced by
 * `ideviceinstaller list --xml` and friends. Nested dictionaries are ignored (their children are
 * not merged into the parent), which is enough for app listings.
 */
object PlistParser {

    fun parseArrayOfDicts(xml: String): List<Map<String, String>> {
        val start = xml.indexOf("<?xml")
        val clean = if (start >= 0) xml.substring(start) else xml
        if (clean.isBlank()) return emptyList()

        val parser = Xml.newPullParser()
        return runCatching {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(clean))

            val result = mutableListOf<Map<String, String>>()
            var current: MutableMap<String, String>? = null
            var pendingKey: String? = null
            var depth = 0

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "dict" -> {
                                depth++
                                if (depth == 1) current = mutableMapOf()
                            }

                            "key" -> pendingKey = parser.nextText()

                            "string", "integer", "real", "date" -> {
                                val value = parser.nextText()
                                val key = pendingKey
                                if (key != null && current != null) current[key] = value
                                pendingKey = null
                            }

                            "true", "false" -> {
                                val key = pendingKey
                                if (key != null && current != null) {
                                    current[key] = parser.name.lowercase()
                                }
                                pendingKey = null
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == "dict") {
                            if (depth == 1) {
                                current?.let { if (it.isNotEmpty()) result += it }
                                current = null
                            }
                            depth--
                            pendingKey = null
                        }
                    }
                }
                event = parser.next()
            }
            result
        }.getOrDefault(emptyList())
    }
}
