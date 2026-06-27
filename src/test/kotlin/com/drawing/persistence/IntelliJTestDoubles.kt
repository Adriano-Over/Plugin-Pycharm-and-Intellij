package com.drawing.persistence

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal fun testProject(basePath: String): Project {
    return proxyFor(Project::class.java) { methodName, returnType, args, proxy ->
        when (methodName) {
            "getBasePath" -> basePath
            "getName" -> "DrawingTestProject"
            "isDisposed" -> false
            "toString" -> "DrawingTestProject($basePath)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultReturnValue(returnType)
        }
    }
}

internal fun testDocument(name: String = "DrawingTestDocument"): Document {
    return proxyFor(Document::class.java) { methodName, returnType, args, proxy ->
        when (methodName) {
            "toString" -> name
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultReturnValue(returnType)
        }
    }
}

private fun <T : Any> proxyFor(
    type: Class<T>,
    implementation: (methodName: String, returnType: Class<*>, args: Array<out Any?>?, proxy: Any) -> Any?
): T {
    val handler = InvocationHandler { proxy, method, args ->
        implementation(method.name, method.returnType, args, proxy)
    }
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
}

private fun defaultReturnValue(returnType: Class<*>): Any? {
    return when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        java.lang.Void.TYPE -> null
        else -> null
    }
}
