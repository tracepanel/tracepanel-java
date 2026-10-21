package dev.tracepanel.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.SessionProvidersKey
import io.ktor.server.sessions.sessions
import io.ktor.utils.io.InternalAPI
import java.lang.reflect.Modifier

/**
 * The call's sessions (Ktor's Sessions plugin) as the call left them, as items by name, for session.* attributes: each
 * session's properties (a data class's, a map's entries), or the session itself, under its provider's name, when it is
 * a plain value. A property two sessions share is the first one's. A session the call cleared is not there.
 *
 * Only called when the Sessions plugin is on the classpath: this file's classes need it. The sessions' names are the
 * plugin's own, which Ktor marks internal: a Ktor that changes them leaves the session out, the caller catches it.
 */
@OptIn(InternalAPI::class)
internal fun sessionItems(call: ApplicationCall): Map<String, Any?> {
    val providers = call.attributes.getOrNull(SessionProvidersKey)
        ?: call.application.attributes.getOrNull(SessionProvidersKey)
        ?: return emptyMap()
    val sessions = call.sessions
    // A deferred session (io.ktor.server.sessions.deferred) would be read from its storage here: left out.
    if (sessions.javaClass.simpleName.contains("Deferred")) return emptyMap()
    val items = linkedMapOf<String, Any?>()
    for (provider in providers) {
        val value = sessions.get(provider.name) ?: continue
        val properties = properties(value)
        if (properties == null) items.putIfAbsent(provider.name, value) else properties.forEach(items::putIfAbsent)
    }
    return items
}

/** An object's properties by name, its fields' values; null for a value that is one as it is (text, a number, a list). */
private fun properties(value: Any): Map<String, Any?>? {
    if (value is Map<*, *>) return value.entries.associate { (key, item) -> key.toString() to item }
    if (value is CharSequence || value is Number || value is Boolean || value is Enum<*> || value is Collection<*>) return null
    val found = linkedMapOf<String, Any?>()
    var type: Class<*>? = value.javaClass
    while (type != null && !type.name.startsWith("java.") && !type.name.startsWith("kotlin.")) {
        for (field in type.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic || !field.trySetAccessible()) continue
            found.putIfAbsent(field.name, field.get(value))
        }
        type = type.superclass
    }
    return found.ifEmpty { null }
}
