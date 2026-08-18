package com.meenseek.jobvis.imports

import jakarta.mail.Part
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64

@Component
class BoundedMimeTextExtractor {
	fun extract(part: Part): String {
		val contentType = part.contentType
		val disposition = part.disposition
		val fileName = runCatching(part::getFileName).getOrNull()
		if (isAttachment(contentType, disposition, fileName)) return ""
		val mediaType = mediaType(contentType)
		return part.inputStream.use { input ->
			if (mediaType.startsWith("multipart/")) {
				val boundary = parameter(contentType, "boundary") ?: return@use ""
				decodeMultipart(LineCursor(input), boundary, mediaType, 0, ParseBudget()).text
			} else {
				decodeTopLevelText(input, contentType, mediaType)
			}
		}.trim().take(PREVIEW_LIMIT)
	}

	private fun decodeTopLevelText(input: InputStream, contentType: String?, mediaType: String): String {
		if (mediaType.isNotEmpty() && !mediaType.startsWith("text/")) return ""
		val bytes = input.readNBytes(MAX_DECODED_BODY_BYTES + 1).let {
			if (it.size > MAX_DECODED_BODY_BYTES) it.copyOf(MAX_DECODED_BODY_BYTES) else it
		}
		val text = String(bytes, charset(contentType))
		return if (mediaType == "text/html") stripHtml(text) else text
	}

	private fun decodeMultipart(
		cursor: LineCursor,
		boundary: String,
		multipartType: String,
		depth: Int,
		budget: ParseBudget,
	): DecodedContent {
		if (depth > MAX_DEPTH) return DecodedContent("", "")
		var boundaryEnd = consumeUntilBoundary(cursor, boundary)
		val children = mutableListOf<DecodedContent>()
		while (boundaryEnd == BoundaryEnd.NEXT && budget.parts < MAX_PARTS) {
			budget.parts++
			val headers = readHeaders(cursor) ?: break
			val child = decodeChild(cursor, headers, boundary, depth + 1, budget)
			if (child.content.text.isNotBlank()) children += child.content
			boundaryEnd = child.boundaryEnd
		}
		if (boundaryEnd == BoundaryEnd.NEXT) {
			while (boundaryEnd == BoundaryEnd.NEXT) {
				readHeaders(cursor) ?: break
				boundaryEnd = consumeUntilBoundary(cursor, boundary)
			}
		}
		if (children.isEmpty()) return DecodedContent("", "")
		if (multipartType == "multipart/alternative") {
			return children.firstOrNull { it.mediaType == "text/plain" } ?: children.first()
		}
		return DecodedContent(
			children.asSequence().map(DecodedContent::text).joinToString("\n").take(PREVIEW_LIMIT),
			children.first().mediaType,
		)
	}

	private fun decodeChild(
		cursor: LineCursor,
		headers: Map<String, String>,
		parentBoundary: String,
		depth: Int,
		budget: ParseBudget,
	): DecodedChild {
		val contentType = headers["content-type"] ?: "text/plain; charset=UTF-8"
		val disposition = headers["content-disposition"]
		val type = mediaType(contentType)
		if (depth > MAX_DEPTH || isAttachment(contentType, disposition, null)) {
			return DecodedChild(DecodedContent("", type), consumeUntilBoundary(cursor, parentBoundary))
		}
		if (type.startsWith("multipart/")) {
			val nestedBoundary = parameter(contentType, "boundary")
			if (nestedBoundary == null) {
				return DecodedChild(DecodedContent("", type), consumeUntilBoundary(cursor, parentBoundary))
			}
			val nested = decodeMultipart(cursor, nestedBoundary, type, depth, budget)
			return DecodedChild(nested, consumeUntilBoundary(cursor, parentBoundary))
		}
		if (type.isNotEmpty() && !type.startsWith("text/")) {
			return DecodedChild(DecodedContent("", type), consumeUntilBoundary(cursor, parentBoundary))
		}
		val encoded = ByteArrayOutputStream(MAX_ENCODED_BODY_BYTES)
		val end = consumeUntilBoundary(cursor, parentBoundary) { line ->
			val remaining = MAX_ENCODED_BODY_BYTES - encoded.size()
			if (remaining > 0) encoded.write(line, 0, minOf(remaining, line.size))
		}
		val decoded = decodeTransfer(encoded.toByteArray(), headers["content-transfer-encoding"])
			.let { if (it.size > MAX_DECODED_BODY_BYTES) it.copyOf(MAX_DECODED_BODY_BYTES) else it }
		val text = String(decoded, charset(contentType)).let {
			if (type == "text/html") stripHtml(it) else it
		}.take(PREVIEW_LIMIT)
		return DecodedChild(DecodedContent(text, type.ifEmpty { "text/plain" }), end)
	}

	private fun consumeUntilBoundary(
		cursor: LineCursor,
		boundary: String,
		onBodyLine: (ByteArray) -> Unit = {},
	): BoundaryEnd {
		val delimiter = "--$boundary"
		val closing = "$delimiter--"
		while (true) {
			val line = cursor.nextLine() ?: return BoundaryEnd.END
			when (line.asBoundaryLine()) {
				delimiter -> return BoundaryEnd.NEXT
				closing -> return BoundaryEnd.CLOSED
				else -> onBodyLine(line)
			}
		}
	}

	private fun readHeaders(cursor: LineCursor): Map<String, String>? {
		val lines = mutableListOf<String>()
		var storedBytes = 0
		while (true) {
			val raw = cursor.nextLine() ?: return null
			val line = raw.toString(StandardCharsets.ISO_8859_1).trimEnd('\r', '\n')
			if (line.isEmpty()) break
			if (storedBytes < MAX_HEADER_BYTES) {
				val remaining = MAX_HEADER_BYTES - storedBytes
				lines += line.take(remaining)
				storedBytes += minOf(remaining, line.length)
			}
		}
		val unfolded = mutableListOf<String>()
		for (line in lines) {
			if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.isNotEmpty()) {
				unfolded[unfolded.lastIndex] = unfolded.last() + " " + line.trim()
			} else {
				unfolded += line
			}
		}
		return unfolded.mapNotNull { line ->
			val colon = line.indexOf(':')
			if (colon <= 0) null
			else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
		}.toMap()
	}

	private fun decodeTransfer(bytes: ByteArray, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase()) {
		"base64" -> runCatching { Base64.getMimeDecoder().decode(bytes) }.getOrDefault(ByteArray(0))
		"quoted-printable" -> decodeQuotedPrintable(bytes)
		else -> bytes
	}

	private fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
		val output = ByteArrayOutputStream(minOf(bytes.size, MAX_DECODED_BODY_BYTES))
		var index = 0
		while (index < bytes.size && output.size() < MAX_DECODED_BODY_BYTES) {
			if (bytes[index].toInt() == '='.code && index + 1 < bytes.size) {
				if (bytes[index + 1].toInt() == '\r'.code && index + 2 < bytes.size &&
					bytes[index + 2].toInt() == '\n'.code
				) {
					index += 3
					continue
				}
				if (bytes[index + 1].toInt() == '\n'.code) {
					index += 2
					continue
				}
				if (index + 2 < bytes.size) {
					val high = hex(bytes[index + 1])
					val low = hex(bytes[index + 2])
					if (high >= 0 && low >= 0) {
						output.write((high shl 4) or low)
						index += 3
						continue
					}
				}
			}
			output.write(bytes[index].toInt())
			index++
		}
		return output.toByteArray()
	}

	private fun hex(value: Byte): Int = when (val char = value.toInt().toChar()) {
		in '0'..'9' -> char - '0'
		in 'A'..'F' -> char - 'A' + 10
		in 'a'..'f' -> char - 'a' + 10
		else -> -1
	}

	private fun parameter(header: String?, name: String): String? {
		if (header == null) return null
		val expression = Regex(
			"(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))",
			RegexOption.IGNORE_CASE,
		)
		val match = expression.find(header) ?: return null
		return (match.groups[1]?.value ?: match.groups[2]?.value)?.takeIf(String::isNotBlank)
	}

	private fun mediaType(contentType: String?): String =
		contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

	private fun charset(contentType: String?): Charset {
		val name = parameter(contentType, "charset") ?: return StandardCharsets.UTF_8
		return runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)
	}

	private fun isAttachment(contentType: String?, disposition: String?, fileName: String?): Boolean =
		disposition?.substringBefore(';')?.trim()?.equals(Part.ATTACHMENT, ignoreCase = true) == true ||
			!fileName.isNullOrBlank() ||
			hasParameter(disposition, "filename") ||
			hasParameter(contentType, "name")

	private fun hasParameter(header: String?, baseName: String): Boolean {
		if (header == null) return false
		return Regex(
			"(?:^|;)\\s*${Regex.escape(baseName)}(?:\\*|\\*\\d+\\*?)?\\s*=",
			RegexOption.IGNORE_CASE,
		).containsMatchIn(header)
	}

	private fun stripHtml(value: String): String = value
		.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
		.replace(Regex("(?s)<[^>]+>"), " ")
		.replace("&nbsp;", " ")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&amp;", "&")
		.replace(Regex("\\s+"), " ")

	private fun ByteArray.asBoundaryLine(): String =
		toString(StandardCharsets.ISO_8859_1).trimEnd('\r', '\n')

	private class LineCursor(private val input: InputStream) {
		private var scannedBytes = 0

		fun nextLine(): ByteArray? {
			if (scannedBytes >= MAX_SCAN_BYTES) return null
			val output = ByteArrayOutputStream()
			var readAny = false
			while (scannedBytes < MAX_SCAN_BYTES) {
				val value = input.read()
				if (value < 0) break
				readAny = true
				scannedBytes++
				if (output.size() < MAX_LINE_BYTES) output.write(value)
				if (value == '\n'.code) break
			}
			return if (readAny) output.toByteArray() else null
		}
	}

	private data class ParseBudget(var parts: Int = 0)
	private data class DecodedContent(val text: String, val mediaType: String)
	private data class DecodedChild(val content: DecodedContent, val boundaryEnd: BoundaryEnd)
	private enum class BoundaryEnd { NEXT, CLOSED, END }

	private companion object {
		const val PREVIEW_LIMIT = 4000
		const val MAX_SCAN_BYTES = 16 * 1024 * 1024
		const val MAX_HEADER_BYTES = 32 * 1024
		const val MAX_LINE_BYTES = 16 * 1024
		const val MAX_ENCODED_BODY_BYTES = 96 * 1024
		const val MAX_DECODED_BODY_BYTES = 64 * 1024
		const val MAX_DEPTH = 10
		const val MAX_PARTS = 100
	}
}
