package fr.tear36.blus.data

import java.io.ByteArrayOutputStream

/** Tiny protobuf writer used to craft fixtures for [GtfsRtTest]. */
class PbBuilder {
    private val body = ByteArrayOutputStream()

    fun string(field: Int, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        varint((field shl 3) or 2)
        varint(data.size)
        body.write(data)
    }

    fun float32(field: Int, value: Float) {
        varint((field shl 3) or 5)
        val bits = java.lang.Float.floatToIntBits(value)
        body.write(bits and 0xFF)
        body.write((bits ushr 8) and 0xFF)
        body.write((bits ushr 16) and 0xFF)
        body.write((bits ushr 24) and 0xFF)
    }

    fun raw(data: ByteArray) {
        body.write(data)
    }

    fun message(field: Int, block: (PbBuilder) -> Unit): PbBuilder {
        val nested = PbBuilder()
        block(nested)
        val data = nested.build()
        varint((field shl 3) or 2)
        varint(data.size)
        body.write(data)
        return this
    }

    fun build(): ByteArray = body.toByteArray()

    private fun varint(value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                body.write(v)
                return
            }
            body.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }
}