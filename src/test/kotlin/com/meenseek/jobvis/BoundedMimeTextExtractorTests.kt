package com.meenseek.jobvis

import com.meenseek.jobvis.imports.BoundedMimeTextExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.Part
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Base64

class BoundedMimeTextExtractorTests {
	private val extractor = BoundedMimeTextExtractor()

	@Test
	fun `큰 MIME 본문은 제한된 미리보기만 읽는다`() {
		val part = MimeBodyPart().apply { setText("a".repeat(200_000), Charsets.UTF_8.name()) }
		assertThat(extractor.extract(part)).hasSize(4000)
	}

	@Test
	fun `MIME content 객체를 만들지 않고 제한된 raw stream만 읽는다`() {
		val part = Mockito.mock(Part::class.java).also {
			Mockito.`when`(it.inputStream).thenReturn(ByteArrayInputStream("safe preview".toByteArray()))
		}
		assertThat(extractor.extract(part)).isEqualTo("safe preview")
		Mockito.verify(part, Mockito.never()).content
	}

	@Test
	fun `multipart의 charset과 전송 인코딩을 제한된 raw stream 안에서 해석하고 첨부는 제외한다`() {
		val korean = "면접 일정 안내".toByteArray(Charset.forName("EUC-KR"))
		val raw = """
			--jobvis-boundary
			Content-Type: text/plain; charset=EUC-KR
			Content-Transfer-Encoding: base64

			${Base64.getMimeEncoder().encodeToString(korean)}
			--jobvis-boundary
			Content-Type: text/plain; charset=UTF-8
			Content-Transfer-Encoding: quoted-printable

			follow=2Dup
			--jobvis-boundary
			Content-Type: text/plain
			Content-Disposition: attachment; filename="secret.txt"

			secret-attachment
			--jobvis-boundary--
		""".trimIndent().replace("\n", "\r\n")
		val part = Mockito.mock(Part::class.java).also {
			Mockito.`when`(it.contentType).thenReturn("multipart/mixed; boundary=\"jobvis-boundary\"")
			Mockito.`when`(it.inputStream).thenReturn(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))
		}

		assertThat(extractor.extract(part))
			.contains("면접 일정 안내")
			.contains("follow-up")
			.doesNotContain("secret-attachment")
		Mockito.verify(part, Mockito.never()).content
	}

	@Test
	fun `inline filename과 content type name이 있는 텍스트 첨부도 본문에서 제외한다`() {
		val raw = """
			--parts
			Content-Type: text/plain; charset=UTF-8

			정상 채용 본문
			--parts
			Content-Type: text/plain; name="resume.txt"
			Content-Disposition: inline; filename="resume.txt"

			첨부파일의 가짜 최종 합격 문구
			--parts--
		""".trimIndent().replace("\n", "\r\n")
		val part = multipart(raw, "parts")

		assertThat(extractor.extract(part))
			.contains("정상 채용 본문")
			.doesNotContain("가짜 최종 합격")
	}

	@Test
	fun `RFC 2231 확장 및 연속 파일명 parameter도 텍스트 첨부로 제외한다`() {
		val raw = """
			--rfc2231
			Content-Type: text/plain; charset=UTF-8

			정상 본문
			--rfc2231
			Content-Type: text/plain; name*=UTF-8''resume.txt
			Content-Disposition: inline; filename*0*=UTF-8''resume; filename*1*=.txt

			첨부 안의 가짜 합격 문구
			--rfc2231--
		""".trimIndent().replace("\n", "\r\n")

		assertThat(extractor.extract(multipart(raw, "rfc2231")))
			.contains("정상 본문")
			.doesNotContain("가짜 합격")
	}

	@Test
	fun `64KiB보다 큰 선행 첨부 뒤의 정상 본문을 streaming 경계 순회로 찾는다`() {
		val raw = buildString {
			append("--large\r\n")
			append("Content-Type: application/octet-stream; name=large.bin\r\n")
			append("Content-Disposition: attachment; filename=large.bin\r\n\r\n")
			append("A".repeat(128 * 1024)).append("\r\n")
			append("--large\r\n")
			append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
			append("뒤에 있는 면접 일정 본문\r\n")
			append("--large--\r\n")
		}

		assertThat(extractor.extract(multipart(raw, "large"))).contains("뒤에 있는 면접 일정 본문")
	}

	private fun multipart(raw: String, boundary: String): Part = Mockito.mock(Part::class.java).also {
		Mockito.`when`(it.contentType).thenReturn("multipart/mixed; boundary=\"$boundary\"")
		Mockito.`when`(it.inputStream).thenReturn(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
	}
}
