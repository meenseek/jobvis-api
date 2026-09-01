package com.meenseek.jobvis

import com.meenseek.jobvis.application.ApplicationResult
import com.meenseek.jobvis.application.ApplicationStage
import com.meenseek.jobvis.application.ScheduleType
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.imports.DeterministicRecruitmentMailAnalyzer
import com.meenseek.jobvis.imports.MailCandidate
import com.meenseek.jobvis.imports.ProviderMailKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class RecruitmentMailAnalyzerTests {
	private val analyzer = DeterministicRecruitmentMailAnalyzer()

	@Test
	fun `Naver Message-ID key는 unfold와 trim만 적용하고 대소문자를 보존한다`() {
		val canonical = ProviderMailKeys.naverStableMessageKey("  <AbC@example.com>  ")
		val unfolded = ProviderMailKeys.naverStableMessageKey("\r\n\t<AbC@example.com>\r\n ")
		val differentCase = ProviderMailKeys.naverStableMessageKey("<abc@example.com>")

		assertThat(canonical)
			.isEqualTo("11e3af5252d90856e0c19ca0917a61817846560d9bb4bc8b6f2bd794e777e77f")
		assertThat(unfolded).isEqualTo(canonical)
		assertThat(differentCase)
			.isEqualTo("a1c01306268e0d2c69f4426068f5e4d0ab1e61c29f34afe61da456b48b8dfea6")
			.isNotEqualTo(canonical)
	}

	@Test
	fun `한국어 오전과 오후를 24시간 시각으로 변환한다`() {
		assertThat(analyze("면접은 2026년 8월 25일 오후 3시 30분입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T06:30:00Z"))
		assertThat(analyze("면접은 2026년 8월 25일 오전 12시입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-24T15:00:00Z"))
		assertThat(analyze("면접은 2026년 8월 25일 오후 12시입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T03:00:00Z"))
		assertThat(analyze("면접은 2026년 8월 25일 오후 3시 반입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T06:30:00Z"))
	}

	@Test
	fun `기존 24시간 표기와 영어 오전 오후도 유지한다`() {
		assertThat(analyze("Interview: 2026-08-25 14:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25 3:20 PM").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T06:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25 AM 12:00").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-24T15:00:00Z"))
		assertThat(analyze("Interview: 2026-08-25T14:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25 I am available at 12:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T03:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25 I am available at 3:20 PM").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T06:20:00Z"))
	}

	@Test
	fun `날짜와 시간 사이의 요일과 구분자를 보존한다`() {
		assertThat(analyze("면접은 2026년 8월 25일 (화) 오후 3시 30분입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T06:30:00Z"))
		assertThat(analyze("면접은 2026년 8월 25일 화요일, 오전 10시입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T01:00:00Z"))
		assertThat(analyze("Interview: 2026-08-25\n14:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25 ${"안내 문구 ".repeat(12)}14:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
	}

	@Test
	fun `영문 단어 내부 표기와 서로 충돌하는 오전 오후를 meridiem으로 사용하지 않는다`() {
		assertThat(analyze("Interview: 2026-08-25 example 14:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25 3:20 PMonday").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-24T18:20:00Z"))
		assertThat(analyze("면접은 2026년 8월 25일 오전 일정에서 오후 3시로 변경되었습니다.").scheduledAt)
			.isNull()
	}

	@Test
	fun `날짜 뒤 별도 문장의 숫자를 시각으로 오인하지 않는다`() {
		assertThat(analyze("면접일은 2026년 8월 25일입니다. 장소는 3층입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(analyze("면접일은 2026년 8월 25일입니다. 장소는 3층, 시작은 14:20입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
		val unrelatedTime = analyze("면접일은 2026년 8월 25일입니다. 문의 가능 시간은 18:00입니다.")
		assertThat(unrelatedTime.scheduledAt).isNull()
		assertThat(unrelatedTime.scheduleType).isNull()
		val sameSentenceUnrelatedTime = analyze("면접일은 2026년 8월 25일, 문의 가능 시간은 18:00입니다.")
		assertThat(sameSentenceUnrelatedTime.scheduledAt).isNull()
		assertThat(sameSentenceUnrelatedTime.scheduleType).isNull()
		val inquiryTime = analyze("면접일은 2026-08-25, 문의 시간은 18:00입니다.")
		assertThat(inquiryTime.scheduledAt).isNull()
		assertThat(inquiryTime.scheduleType).isNull()
		val lineWrappedInquiryTime = analyze("면접일은 2026-08-25입니다. 문의 시간은\n18:00입니다.")
		assertThat(lineWrappedInquiryTime.scheduledAt).isNull()
		assertThat(lineWrappedInquiryTime.scheduleType).isNull()
		val duration = analyze("면접일은 2026-08-25이고 예상 소요 시간은 01:30입니다.")
		assertThat(duration.scheduledAt).isNull()
		assertThat(duration.scheduleType).isNull()
		val lineWrappedDuration = analyze("면접일은 2026-08-25입니다. 예상 소요 시간은\n01:30입니다.")
		assertThat(lineWrappedDuration.scheduledAt).isNull()
		assertThat(lineWrappedDuration.scheduleType).isNull()
		assertThat(
			analyze("면접일은 2026년 8월 25일, 시작은 14:00, 문의 가능 시간은 18:00입니다.").scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T05:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 문의 가능 시간은 오전 10시입니다. 면접 시작은 오후 3시입니다.")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 문의 가능 시간은 오전 10시입니다. 면접은 오후 3시입니다.")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 문의 가능 시간은 오전 10시이며 면접은 오후 3시입니다.")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 문의 가능 시간은 오전 10시\n면접은 오후 3시입니다.")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 문의 오전 10시\n면접 오후 3시")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 문의 오전 10시\n오후 3시 면접")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25이며 면접은 15:00이고 예상 소요 시간은 01:30입니다.")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25, 문의 가능 시간은 오전 10시, 면접 시작은 오후 3시입니다.")
				.scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(analyze("코딩 테스트는 2026년 8월 25일이며 소요 시간은 3시간입니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(analyze("코딩 테스트는 2026년 8월 25일 오후 3시간 동안 진행됩니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(analyze("코딩 테스트는 2026년 8월 25일 오후 3 시간 동안 진행됩니다.").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(analyze("Interview: 2026-08-25 PM 3rd round").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
	}

	@Test
	fun `서로 다른 날짜가 섞인 메일은 일정으로 임의 결합하지 않는다`() {
		val result = analyze("지원 마감 2026-08-25, 면접 2026-08-26 14:20")

		assertThat(result.scheduledAt).isNull()
		assertThat(result.scheduleEndsAt).isNull()
		assertThat(result.scheduleType).isNull()
		assertThat(result.scheduleAction).isNull()
	}

	@Test
	fun `일정 유형은 날짜가 있는 문맥에서 결정하고 모호하면 추출하지 않는다`() {
		val deadline = analyze("지원 마감 2026-08-25")
		assertThat(deadline.scheduleType).isEqualTo(ScheduleType.APPLICATION)
		assertThat(deadline.scheduleAction).isEqualTo("지원 마감")

		val ambiguous = analyze("면접 예약 마감 2026-08-25")
		assertThat(ambiguous.scheduledAt).isNull()
		assertThat(ambiguous.scheduleType).isNull()

		val mixedOccurrences = analyze(
			"면접은 2026-08-25 오후 3시입니다.",
			"[Acme] 코딩 테스트 2026-08-25 일정",
		)
		assertThat(mixedOccurrences.scheduledAt).isNull()
		assertThat(mixedOccurrences.scheduleType).isNull()
	}

	@Test
	fun `같은 날짜에 서로 다른 명시적 시각이 있으면 일정 추출을 보류한다`() {
		val changed = analyze("면접 일정이 2026-08-25 14:00에서 15:00로 변경되었습니다.")
		assertThat(changed.scheduledAt).isNull()
		assertThat(changed.scheduleType).isNull()

		val repeated = analyze(
			"면접은 2026-08-25 15:00입니다.",
			"[Acme] 면접 2026-08-25 14:00 안내",
		)
		assertThat(repeated.scheduledAt).isNull()
		assertThat(repeated.scheduleType).isNull()

		val changedBareHours = analyze("면접일 2026-08-25 14, 변경 면접일 2026-08-25 15")
		assertThat(changedBareHours.scheduledAt).isNull()
		assertThat(changedBareHours.scheduleType).isNull()

		val bareAndExplicit = analyze("면접일 2026-08-25 14, 기존 시간은 15:00입니다.")
		assertThat(bareAndExplicit.scheduledAt).isNull()
		assertThat(bareAndExplicit.scheduleType).isNull()

		val malformedInBody = analyze(
			"확정 면접 2026-08-25 15:0",
			"[Acme] 면접 2026-08-25 14:00",
		)
		assertThat(malformedInBody.scheduledAt).isNull()
		assertThat(malformedInBody.scheduleType).isNull()

		val malformedInSubject = analyze(
			"확정 면접 2026-08-25 14:00",
			"[Acme] 면접 2026-08-25 15:0",
		)
		assertThat(malformedInSubject.scheduledAt).isNull()
		assertThat(malformedInSubject.scheduleType).isNull()

		val changedInBody = analyze(
			"시간이 15:00로 변경되었습니다.",
			"[Acme] 면접 2026-08-25 14:00",
		)
		assertThat(changedInBody.scheduledAt).isNull()
		assertThat(changedInBody.scheduleType).isNull()

		val laterMalformedTime = analyze("면접 2026-08-25 14:00에서 15:0로 변경되었습니다.")
		assertThat(laterMalformedTime.scheduledAt).isNull()
		assertThat(laterMalformedTime.scheduleType).isNull()

		val leadingChangedTime = analyze(
			"면접은 2026-08-25 14:00입니다.",
			"[Acme] 면접 시간이 15:00로 변경",
		)
		assertThat(leadingChangedTime.scheduledAt).isNull()
		assertThat(leadingChangedTime.scheduleType).isNull()

		val leadingMalformedTime = analyze(
			"면접은 2026-08-25 14:00입니다.",
			"[Acme] 면접 시간이 15:0로 변경",
		)
		assertThat(leadingMalformedTime.scheduledAt).isNull()
		assertThat(leadingMalformedTime.scheduleType).isNull()

		val newTimeWithoutKnownCue = analyze(
			"일정이 변경되었습니다. 새 시간은 15:00입니다.",
			"[Acme] 면접 2026-08-25 14:00 안내",
		)
		assertThat(newTimeWithoutKnownCue.scheduledAt).isNull()
		assertThat(newTimeWithoutKnownCue.scheduleType).isNull()
	}

	@Test
	fun `같은 날짜의 반복 표기는 뒤따르는 명시적 시각을 사용할 수 있다`() {
		assertThat(analyze("면접일 2026-08-25, 확정 면접 2026-08-25 14:20").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))

		val repeatedAcrossFields = analyze(
			"면접은 2026-08-25 오후 3시입니다.",
			"[Acme] 2026-08-25 일정 안내",
		)
		assertThat(repeatedAcrossFields.scheduledAt).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(repeatedAcrossFields.scheduleType).isEqualTo(ScheduleType.INTERVIEW)
		assertThat(repeatedAcrossFields.scheduleAction).isEqualTo("면접")

		val dateInSubject = analyze(
			"오후 3시에 진행됩니다.",
			"[Acme] 면접 2026-08-25 일정 안내",
		)
		assertThat(dateInSubject.scheduledAt).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(dateInSubject.scheduleType).isEqualTo(ScheduleType.INTERVIEW)
		assertThat(analyze("Interview: 2026-08-25 3 PM").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))

		val timeInSubject = analyze(
			"면접일은 2026-08-25입니다.",
			"[Acme] 면접 시간 15:00 안내",
		)
		assertThat(timeInSubject.scheduledAt).isEqualTo(Instant.parse("2026-08-25T06:00:00Z"))
		assertThat(timeInSubject.scheduleType).isEqualTo(ScheduleType.INTERVIEW)
	}

	@Test
	fun `URL 내부 날짜와 필드 사이 일정 유형 충돌은 일정으로 확정하지 않는다`() {
		val urlInBody = analyze("자세한 내용 https://careers.example.com/2026/08/25/details")
		assertThat(urlInBody.scheduledAt).isNull()
		assertThat(urlInBody.scheduleType).isNull()

		val urlInSubject = analyze(
			"자세한 내용은 본문을 확인하세요.",
			"[Acme] 면접 https://careers.example.com/2026-08-25/details",
		)
		assertThat(urlInSubject.scheduledAt).isNull()
		assertThat(urlInSubject.scheduleType).isNull()

		val schemeLessUrl = analyze("자세한 내용 careers.example.com/2026-08-25/14:00")
		assertThat(schemeLessUrl.scheduledAt).isNull()
		assertThat(schemeLessUrl.scheduleType).isNull()

		assertThat(
			analyze("면접일은 2026-08-25입니다. 변경 링크 https://example.com/changed/15:00").scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 링크 https://example.com/changed/15:0").scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 링크 https://example.com/timezone/UTC").scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))
		assertThat(
			analyze("면접일은 2026-08-25입니다. 링크 example.com/changed/15:00").scheduledAt,
		).isEqualTo(Instant.parse("2026-08-25T00:00:00Z"))

		val testSubjectInterviewBody = analyze(
			"면접은 2026-08-25 14:00입니다.",
			"[Acme] 코딩 테스트 안내",
		)
		assertThat(testSubjectInterviewBody.scheduledAt).isNull()
		assertThat(testSubjectInterviewBody.scheduleType).isNull()

		val interviewSubjectTestBody = analyze(
			"코딩 테스트는 2026-08-25 14:00입니다.",
			"[Acme] 면접 안내",
		)
		assertThat(interviewSubjectTestBody.scheduledAt).isNull()
		assertThat(interviewSubjectTestBody.scheduleType).isNull()
	}

	@Test
	fun `날짜와 시각의 숫자 토큰 경계를 검증한다`() {
		assertThat(analyze("Interview: 2026-08-250 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:200").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 예정 시간 14:200").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 예정 시간 14:200 문의 02:00").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 예정 시간 14:2").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 예정 시간 14:").scheduledAt).isNull()
		assertThat(analyze("면접: 2026년 8월 25일0 안내, 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25T14:20Z").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25T14:20+09:00").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 (UTC)").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20, UTC").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 UTC 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25T14:20:00Z").scheduledAt).isNull()
		assertThat(analyze("Interview: UTC 2026-08-25 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: (UTC) 2026-08-25 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: UTC+09:00 2026-08-25 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20", "[Acme] Interview UTC").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 Asia/Seoul").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20. https://example.com/apply").scheduledAt)
			.isEqualTo(Instant.parse("2026-08-25T05:20:00Z"))
		assertThat(analyze("Interview: 2026-08-25T14:20:10").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25T14:20:99").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25T14:20:10에서 14:20:50로 변경").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 EST").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 HKT").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 est").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 ist").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20 bst").scheduledAt).isNull()
		assertThat(analyze("Interview (EST): 2026-08-25 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview (ist): 2026-08-25 14:20").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25 14:20", "[Acme] Interview bst").scheduledAt).isNull()
		assertThat(analyze("Interview: 2026-08-25T14:20-05").scheduledAt).isNull()
		assertThat(analyze("면접은 2026년 8월 25일 오후 3시 30분 45초입니다.").scheduledAt).isNull()
		assertThat(analyze("면접은 2026년 8월 25일 오후 3시 123분입니다.").scheduledAt).isNull()
		assertThat(analyze("면접은 2026년 8월 25일 오후 3시 30분 123초입니다.").scheduledAt).isNull()
	}

	@Test
	fun `코딩 테스트 메일은 별도 테스트 단계로 분류한다`() {
		val result = analyze("코딩 테스트는 2026년 8월 25일 오후 3시입니다.")

		assertThat(result.stage).isEqualTo(ApplicationStage.TEST)
		assertThat(result.highestStageReached).isEqualTo(ApplicationStage.TEST)
		assertThat(result.scheduleType).isEqualTo(ScheduleType.TEST)
	}

	@Test
	fun `URL의 채용 키워드는 단계와 결과를 변경하지 않는다`() {
		val interviewLink = analyze(
			"자세한 내용 https://careers.example.com/interview-guide",
			"[Acme] 지원 접수",
		)
		assertThat(interviewLink.stage).isEqualTo(ApplicationStage.APPLIED)
		assertThat(interviewLink.highestStageReached).isEqualTo(ApplicationStage.APPLIED)
		assertThat(interviewLink.screeningPassed).isFalse()

		val rejectedLink = analyze(
			"상태 확인 careers.example.com/rejected/hired",
			"[Acme] 지원 접수",
		)
		assertThat(rejectedLink.stage).isEqualTo(ApplicationStage.APPLIED)
		assertThat(rejectedLink.result).isEqualTo(ApplicationResult.ACTIVE)
	}

	private fun analyze(text: String, subject: String = "[Acme] 채용 안내") = requireNotNull(
		analyzer.analyze(
			MailCandidate(
				ConnectionProvider.GMAIL,
				"message-id",
				subject,
				"recruit@example.com",
				Instant.parse("2026-08-20T00:00:00Z"),
				text,
			),
		),
	)
}
