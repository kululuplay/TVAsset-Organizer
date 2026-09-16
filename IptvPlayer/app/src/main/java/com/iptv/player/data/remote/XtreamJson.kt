package com.iptv.player.data.remote

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.lang.reflect.ParameterizedType

/** Tolerant optional metadata, strict identity and response envelopes. */
object XtreamJson {
    fun create(onRejectedRows: (Int) -> Unit = {}): Gson = GsonBuilder()
        .registerTypeAdapterFactory(ContractAdapters(onRejectedRows))
        .create()

    private val rows = setOf(
        XtreamCategory::class.java, XtreamLiveStream::class.java,
        XtreamVodStream::class.java, XtreamSeriesItem::class.java,
        XtreamEpisode::class.java, XtreamEpgEntry::class.java,
    )
    /**
     * Nested metadata blocks that real panels emit as `[]`, `null` or `""` when
     * empty. Any non-object shape is read as "absent" instead of failing the
     * whole response (which would leave a series without episodes forever).
     */
    private val optionalModels = setOf(
        ServerInfo::class.java, XtreamVodDetail::class.java,
        XtreamSeriesDetail::class.java, XtreamEpisodeInfo::class.java,
    )
    private val models = rows + optionalModels + setOf(
        XtreamAuth::class.java, UserInfo::class.java,
        XtreamVodInfo::class.java, XtreamMovieData::class.java,
        XtreamSeriesInfo::class.java, XtreamEpgListing::class.java,
    )

    private const val MAX_SAFE_INTEGRAL = 9007199254740992.0 // 2^53
    private const val MAX_CATEGORY_ID_LENGTH = 128

    /** Accepts 12, "12" and 12.0 (integral doubles) but never "12.5", "../12" or NaN. */
    private fun integralLong(primitive: JsonPrimitive): Long? {
        val text = primitive.asString.trim()
        text.toLongOrNull()?.let { return it }
        val value = text.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value != Math.floor(value) || Math.abs(value) > MAX_SAFE_INTEGRAL) return null
        return value.toLong()
    }

    private class ContractAdapters(private val onRejectedRows: (Int) -> Unit) : TypeAdapterFactory {
        override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            val raw = type.rawType
            val elementType = (type.type as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
            // Kotlin's List<T> may arrive as List<? extends T> (not Class<T>).
            val elementClass = elementType?.let { TypeToken.get(it).rawType }
            if (raw == List::class.java && elementClass in rows) {
                val itemAdapter = gson.getAdapter(TypeToken.get(elementType!!))
                @Suppress("UNCHECKED_CAST")
                return object : TypeAdapter<List<Any>>() {
                    override fun read(reader: JsonReader): List<Any> {
                        // PHP panels serialise a season's episodes as {"0":{..},"1":{..}}
                        // once a key is missing; accept the values in key order.
                        val objectShaped = reader.peek() == JsonToken.BEGIN_OBJECT &&
                            elementClass == XtreamEpisode::class.java
                        if (!objectShaped && reader.peek() != JsonToken.BEGIN_ARRAY) {
                            throw JsonParseException("Expected Xtream list")
                        }
                        val accepted = mutableListOf<Any>()
                        var rejected = 0
                        if (objectShaped) reader.beginObject() else reader.beginArray()
                        while (reader.hasNext()) {
                            if (objectShaped) reader.nextName()
                            // One row at a time: a broken optional field must not
                            // discard the entire catalogue or allocate a second tree.
                            val row = JsonParser.parseReader(reader)
                            try {
                                accepted += itemAdapter.fromJsonTree(row)
                                    ?: throw JsonParseException("Null Xtream row")
                            } catch (_: JsonParseException) {
                                rejected++
                            }
                        }
                        if (objectShaped) reader.endObject() else reader.endArray()
                        if (rejected > 0) onRejectedRows(rejected)
                        if (rejected > 0 && accepted.isEmpty()) {
                            throw JsonParseException("No valid Xtream rows")
                        }
                        return accepted
                    }
                    override fun write(writer: JsonWriter, value: List<Any>?) {
                        if (value == null) { writer.nullValue(); return }
                        writer.beginArray()
                        value.forEach { gson.toJson(it, it.javaClass, writer) }
                        writer.endArray()
                    }
                } as TypeAdapter<T>
            }
            if (raw !in models) return null
            val delegate = gson.getDelegateAdapter(this, type)
            return object : TypeAdapter<T>() {
                @Suppress("UNCHECKED_CAST")
                override fun read(reader: JsonReader): T {
                    val tree = JsonParser.parseReader(reader)
                    if (!tree.isJsonObject) {
                        // "info": [] / null / "" on an optional block means "no metadata".
                        if (raw in optionalModels) return null as T
                        throw JsonParseException("Expected Xtream object")
                    }
                    val value = tree.asJsonObject
                    raw.declaredFields.forEach { field ->
                        val name = field.getAnnotation(SerializedName::class.java) ?: return@forEach
                        (listOf(name.value) + name.alternate).forEach fieldValue@{ key ->
                            val supplied = value.get(key) ?: return@fieldValue
                            if (supplied.isJsonNull) return@fieldValue
                            when (field.type) {
                                String::class.java -> if (!supplied.isJsonPrimitive) {
                                    value.add(key, JsonNull.INSTANCE)
                                } else if (supplied.asJsonPrimitive.isNumber) {
                                    // 12.0 and 12 are the same identifier; keep one spelling
                                    // so category/stream ids join across responses.
                                    integralLong(supplied.asJsonPrimitive)?.let {
                                        value.add(key, JsonPrimitive(it.toString()))
                                    }
                                }
                                Int::class.javaObjectType, Long::class.javaObjectType -> {
                                    val number = if (supplied.isJsonPrimitive) {
                                        val primitive = supplied.asJsonPrimitive
                                        if (primitive.isBoolean && key in setOf("auth", "tv_archive")) {
                                            if (primitive.asBoolean) 1L else 0L
                                        } else integralLong(primitive)
                                    } else null
                                    val inRange = number != null &&
                                        (field.type != Int::class.javaObjectType || number in Int.MIN_VALUE..Int.MAX_VALUE)
                                    value.add(key, if (inRange) JsonPrimitive(number) else JsonNull.INSTANCE)
                                }
                            }
                        }
                    }
                    validate(raw, value)
                    return delegate.fromJsonTree(value)
                }
                override fun write(writer: JsonWriter, value: T) = delegate.write(writer, value)
            }
        }
    }

    private fun validate(raw: Class<*>, value: JsonObject) {
        val idKey = when (raw) {
            XtreamCategory::class.java -> "category_id"
            XtreamLiveStream::class.java, XtreamVodStream::class.java, XtreamMovieData::class.java -> "stream_id"
            XtreamSeriesItem::class.java -> "series_id"
            XtreamEpisode::class.java -> "id"
            else -> null
        }
        if (raw == XtreamCategory::class.java) {
            // Category ids are opaque strings on some panels ("movies-en"); only
            // reject what cannot serve as a key or a query parameter.
            val id = value.get(idKey)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            if (id.isNullOrEmpty() || id.length > MAX_CATEGORY_ID_LENGTH || id.any { it.isISOControl() }) {
                throw JsonParseException("Invalid Xtream category identifier")
            }
        } else if (idKey != null) {
            val id = value.get(idKey)?.takeIf { it.isJsonPrimitive }?.let { integralLong(it.asJsonPrimitive) }
            if (id == null || id < 1) throw JsonParseException("Invalid Xtream identifier")
        }
        when (raw) {
            XtreamAuth::class.java -> if (value.get("user_info")?.isJsonObject != true)
                throw JsonParseException("Missing Xtream user_info")
            UserInfo::class.java -> if (value.get("auth")?.takeIf { it.isJsonPrimitive }?.asInt !in listOf(0, 1))
                throw JsonParseException("Invalid Xtream authentication result")
            XtreamVodInfo::class.java -> if (value.get("movie_data")?.isJsonObject != true)
                throw JsonParseException("Missing Xtream movie_data")
            XtreamSeriesInfo::class.java -> {
                // Some compatible panels represent an empty episode map as [].
                val episodes = value.get("episodes")
                if (episodes?.isJsonArray == true && episodes.asJsonArray.size() == 0) {
                    value.add("episodes", JsonObject())
                } else if (episodes?.isJsonObject != true) {
                    throw JsonParseException("Invalid Xtream episode map")
                }
            }
            XtreamEpgListing::class.java -> if (value.get("epg_listings")?.isJsonArray != true)
                throw JsonParseException("Missing Xtream epg_listings")
        }
    }
}
