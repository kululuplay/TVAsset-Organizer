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
    private val models = rows + setOf(
        XtreamAuth::class.java, UserInfo::class.java, ServerInfo::class.java,
        XtreamVodInfo::class.java, XtreamVodDetail::class.java, XtreamMovieData::class.java,
        XtreamSeriesInfo::class.java, XtreamSeriesDetail::class.java,
        XtreamEpisodeInfo::class.java, XtreamEpgListing::class.java,
    )

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
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                            throw JsonParseException("Expected Xtream list")
                        }
                        val accepted = mutableListOf<Any>()
                        var rejected = 0
                        reader.beginArray()
                        while (reader.hasNext()) {
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
                        reader.endArray()
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
                override fun read(reader: JsonReader): T {
                    val tree = JsonParser.parseReader(reader)
                    if (!tree.isJsonObject) throw JsonParseException("Expected Xtream object")
                    val value = tree.asJsonObject
                    raw.declaredFields.forEach { field ->
                        val name = field.getAnnotation(SerializedName::class.java) ?: return@forEach
                        (listOf(name.value) + name.alternate).forEach fieldValue@{ key ->
                            val supplied = value.get(key) ?: return@fieldValue
                            if (supplied.isJsonNull) return@fieldValue
                            when (field.type) {
                                String::class.java -> if (!supplied.isJsonPrimitive) value.add(key, JsonNull.INSTANCE)
                                Int::class.javaObjectType, Long::class.javaObjectType -> {
                                    val number = if (supplied.isJsonPrimitive) {
                                        val primitive = supplied.asJsonPrimitive
                                        if (primitive.isBoolean && key in setOf("auth", "tv_archive")) {
                                            if (primitive.asBoolean) 1L else 0L
                                        } else primitive.asString.toLongOrNull()
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
        if (idKey != null) {
            val id = value.get(idKey)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
            if (id == null || id < if (raw == XtreamCategory::class.java) 0 else 1) {
                throw JsonParseException("Invalid Xtream identifier")
            }
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
