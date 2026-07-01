package com.drawing

import java.awt.image.BufferedImage

private const val MAX_CACHED_ANNOTATION_IMAGES = 256

internal class AnnotationImageCache {
    private data class CacheKey(
        val id: Long,
        val text: String,
        val colorRgb: Int,
        val width: Int,
        val height: Int,
        val kind: AnnotationKind,
        val style: BalloonTextStyle
    )

    private val imagesByKey = object : LinkedHashMap<CacheKey, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, BufferedImage>?): Boolean {
            return size > MAX_CACHED_ANNOTATION_IMAGES
        }
    }

    fun getOrRender(annotation: AnnotationPath): BufferedImage {
        val key = CacheKey(
            id = annotation.id,
            text = annotation.text,
            colorRgb = annotation.color.rgb,
            width = annotation.width,
            height = annotation.height,
            kind = annotation.kind,
            style = annotation.style
        )
        imagesByKey[key]?.let { image ->
            DrawingPerformanceDiagnostics.recordAnnotationImageCacheHit()
            return image
        }
        DrawingPerformanceDiagnostics.recordAnnotationImageCacheMiss()
        return AnnotationRenderer.render(annotation).also { image ->
            imagesByKey[key] = image
        }
    }

    fun invalidate(annotationId: Long) {
        imagesByKey.keys.removeIf { it.id == annotationId }
    }

    fun clear() {
        imagesByKey.clear()
    }
}
