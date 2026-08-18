package com.meenseek.jobvis.imports

import com.meenseek.jobvis.application.ApplicationResult
import com.meenseek.jobvis.application.ApplicationStage
import com.meenseek.jobvis.application.ScheduleType
import com.meenseek.jobvis.connection.ConnectionProvider
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MailCandidate(
	val provider: ConnectionProvider,
	val providerMessageId: String,
	val subject: String,
	val sender: String,
	val receivedAt: Instant,
	val textPreview: String,
)

data class AnalyzedMailCandidate(
	val sourceSummary: String,
	val company: String,
	val position: String,
	val location: String,
	val employmentType: String,
	val appliedAt: LocalDate,
	val stage: ApplicationStage,
	val highestStageReached: ApplicationStage,
	val screeningPassed: Boolean,
	val result: ApplicationResult,
	val scheduleType: ScheduleType?,
	val scheduleAction: String?,
	val scheduledAt: Instant?,
	val scheduleEndsAt: Instant?,
	val confidence: BigDecimal,
)

fun interface RecruitmentMailAnalyzer {
	fun analyze(candidate: MailCandidate): AnalyzedMailCandidate?
}

@Component
class DeterministicRecruitmentMailAnalyzer : RecruitmentMailAnalyzer {
	override fun analyze(candidate: MailCandidate): AnalyzedMailCandidate? {
		val normalizedText = "${candidate.subject} ${candidate.sender} ${candidate.textPreview}"
			.lowercase(Locale.ROOT)
		if (RECRUITMENT_KEYWORDS.none(normalizedText::contains)) return null

		val rejected = REJECTION_KEYWORDS.any(normalizedText::contains)
		val offered = !rejected && OFFER_KEYWORDS.any(normalizedText::contains)
		val interview = INTERVIEW_KEYWORDS.any(normalizedText::contains)
		val test = TEST_KEYWORDS.any(normalizedText::contains)
		val screening = SCREENING_KEYWORDS.any(normalizedText::contains)
		val stage = when {
			offered -> ApplicationStage.OFFER
			interview -> ApplicationStage.INTERVIEW
			test || screening || rejected -> ApplicationStage.SCREENING
			else -> ApplicationStage.APPLIED
		}
		val result = when {
			rejected -> ApplicationResult.REJECTED
			offered -> ApplicationResult.OFFERED
			else -> ApplicationResult.ACTIVE
		}
		val schedule = extractSchedule(candidate, normalizedText, interview, test)
		val confidence = confidence(candidate, stage, schedule != null)
		return AnalyzedMailCandidate(
			sourceSummary = summarize(candidate),
			company = extractCompany(candidate),
			position = extractPosition(candidate.subject),
			location = "근무지 확인 필요",
			employmentType = "고용 형태 확인 필요",
			appliedAt = LocalDate.ofInstant(candidate.receivedAt, SEOUL),
			stage = stage,
			highestStageReached = stage,
			screeningPassed = stage.passedScreeningByProgress(),
			result = result,
			scheduleType = schedule?.type,
			scheduleAction = schedule?.action,
			scheduledAt = schedule?.startsAt,
			scheduleEndsAt = schedule?.endsAt,
			confidence = confidence,
		)
	}

	private fun summarize(candidate: MailCandidate): String =
		("${candidate.subject} · ${candidate.textPreview}")
			.replace(WHITESPACE, " ")
			.trim()
			.take(1000)

	private fun extractCompany(candidate: MailCandidate): String {
		val bracket = BRACKET_COMPANY.find(candidate.subject)?.groupValues?.get(1)?.trim()
		if (!bracket.isNullOrBlank() && bracket.lowercase(Locale.ROOT) !in GENERIC_BRACKETS) return bracket.take(160)

		val displayName = candidate.sender.substringBefore('<').trim().trim('"')
		if (displayName.isNotBlank() && '@' !in displayName && NO_REPLY_WORDS.none {
			displayName.lowercase(Locale.ROOT).contains(it)
		}) return displayName.take(160)

		val domain = EMAIL_DOMAIN.find(candidate.sender)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
		val label = domain?.split('.')?.let { parts -> parts.getOrNull(parts.lastIndex - 1) }
		return label?.replaceFirstChar { it.titlecase(Locale.ROOT) }?.takeIf { it !in GENERIC_DOMAINS }
			?.take(160)
			?: "회사 확인 필요"
	}

	private fun extractPosition(subject: String): String {
		val normalizedSubject = subject.replace(LEADING_BRACKET, "").trim()
		val afterSeparator = normalizedSubject.split('·', '|', '-').map(String::trim).firstOrNull { part ->
			part.length in 2..160 && POSITION_KEYWORDS.any { keyword -> part.contains(keyword, ignoreCase = true) }
		}
		return afterSeparator?.take(160) ?: "포지션 확인 필요"
	}

	private fun extractSchedule(
		candidate: MailCandidate,
		normalizedText: String,
		interview: Boolean,
		test: Boolean,
	): ExtractedSchedule? {
		val match = DATE_TIME.find("${candidate.subject} ${candidate.textPreview}") ?: return null
		val year = match.groupValues[1].toIntOrNull() ?: return null
		val month = match.groupValues[2].toIntOrNull() ?: return null
		val day = match.groupValues[3].toIntOrNull() ?: return null
		val hour = match.groupValues[4].toIntOrNull() ?: 9
		val minute = match.groupValues[5].toIntOrNull() ?: 0
		val startsAt = runCatching {
			LocalDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute)).atZone(SEOUL).toInstant()
		}.getOrNull() ?: return null
		val type = when {
			interview -> ScheduleType.INTERVIEW
			test -> ScheduleType.TEST
			DEADLINE_KEYWORDS.any(normalizedText::contains) -> ScheduleType.APPLICATION
			else -> ScheduleType.OTHER
		}
		val action = when (type) {
			ScheduleType.INTERVIEW -> "면접"
			ScheduleType.TEST -> "채용 과제 또는 테스트"
			ScheduleType.APPLICATION -> "지원 마감"
			else -> "채용 일정 확인"
		}
		return ExtractedSchedule(type, action, startsAt, startsAt.plusSeconds(3600))
	}

	private fun confidence(candidate: MailCandidate, stage: ApplicationStage, hasSchedule: Boolean): BigDecimal {
		var score = 0.45
		if (BRACKET_COMPANY.containsMatchIn(candidate.subject)) score += 0.15
		if (stage != ApplicationStage.APPLIED) score += 0.15
		if (hasSchedule) score += 0.15
		if (candidate.textPreview.isNotBlank()) score += 0.05
		return BigDecimal.valueOf(score.coerceAtMost(0.95)).setScale(3)
	}

	private data class ExtractedSchedule(
		val type: ScheduleType,
		val action: String,
		val startsAt: Instant,
		val endsAt: Instant,
	)

	private companion object {
		val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		val WHITESPACE = Regex("\\s+")
		val BRACKET_COMPANY = Regex("^[\\s\\[\\(【]+([^\\]\\)】]{2,160})[\\]\\)】]")
		val LEADING_BRACKET = Regex("^[\\s\\[\\(【]+[^\\]\\)】]{2,160}[\\]\\)】]")
		val EMAIL_DOMAIN = Regex("@([A-Za-z0-9.-]+)")
		val DATE_TIME = Regex(
			"(20\\d{2})[년./-]\\s*(\\d{1,2})[월./-]\\s*(\\d{1,2})(?:일)?(?:\\s+[^(\\d)]*(\\d{1,2})(?::(\\d{2}))?)?",
		)
		val RECRUITMENT_KEYWORDS = setOf(
			"채용", "지원", "서류", "면접", "인터뷰", "전형", "과제", "코딩테스트",
			"application", "applicant", "interview", "recruit", "assessment", "candidate", "offer",
		)
		val REJECTION_KEYWORDS = setOf("불합격", "탈락", "아쉽게", "not selected", "rejected", "regret to inform")
		val OFFER_KEYWORDS = setOf("최종 합격", "입사 제안", "처우 협의", "job offer", "offer letter", "hired")
		val INTERVIEW_KEYWORDS = setOf("면접", "인터뷰", "interview")
		val TEST_KEYWORDS = setOf("과제", "코딩테스트", "코딩 테스트", "assessment", "coding test")
		val SCREENING_KEYWORDS = setOf("서류", "검토", "screening", "resume review")
		val DEADLINE_KEYWORDS = setOf("마감", "deadline", "due")
		val POSITION_KEYWORDS = setOf("개발", "엔지니어", "engineer", "developer", "manager", "designer", "analyst")
		val NO_REPLY_WORDS = setOf("noreply", "no-reply", "recruit", "채용")
		val GENERIC_BRACKETS = setOf("채용", "안내", "알림", "recruiting")
		val GENERIC_DOMAINS = setOf("gmail", "naver", "outlook", "greenhouse", "lever", "workday")
	}
}
