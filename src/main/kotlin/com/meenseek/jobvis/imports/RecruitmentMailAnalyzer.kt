package com.meenseek.jobvis.imports

import com.meenseek.jobvis.application.ApplicationResult
import com.meenseek.jobvis.application.ApplicationStage
import com.meenseek.jobvis.application.ScheduleType
import com.meenseek.jobvis.connection.ConnectionProvider
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

data class MailCandidate(
	val provider: ConnectionProvider,
	val providerMessageId: String,
	val subject: String,
	val sender: String,
	val receivedAt: Instant,
	val textPreview: String,
	val providerProcessKeys: Set<String> = emptySet(),
	val stableProviderMessageKey: String? = null,
)

object ProviderMailKeys {
	fun gmailProcessKey(threadId: String?): Set<String> =
		threadId?.trim()?.takeIf(String::isNotEmpty)?.let { setOf(hash("gmail:$it")) } ?: emptySet()

	fun outlookProcessKey(conversationId: String?): Set<String> =
		conversationId?.trim()?.takeIf(String::isNotEmpty)?.let { setOf(hash("outlook:$it")) } ?: emptySet()

	fun naverStableMessageKey(messageId: String?): String? =
		normalizeMessageId(messageId)?.let(::hash)

	fun naverProcessKeys(references: String?, inReplyTo: String?, messageId: String?): Set<String> {
		val referencesIds = messageIds(references)
		val parentId = messageIds(inReplyTo).firstOrNull()
		val currentId = normalizeMessageId(messageId)
		val selected = referencesIds.firstOrNull() ?: parentId ?: currentId
		return listOfNotNull(selected, currentId).map(::hash).toSortedSet()
	}

	private fun messageIds(value: String?): List<String> = value
		?.replace(UNFOLD, " ")
		?.let { raw -> MESSAGE_ID.findAll(raw).map { normalizeMessageId(it.value) }.filterNotNull().toList() }
		.orEmpty()

	private fun normalizeMessageId(value: String?): String? = value
		?.replace(UNFOLD, " ")
		?.trim()
		?.takeIf(String::isNotEmpty)

	private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }

	private val UNFOLD = Regex("\\r?\\n[\\t ]+")
	private val MESSAGE_ID = Regex("<[^<>\\s]+>")
}

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
		val semanticSubject = maskUrls(candidate.subject)
		val semanticPreview = maskUrls(candidate.textPreview)
		val normalizedText = "$semanticSubject ${candidate.sender} $semanticPreview"
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
			test -> ApplicationStage.TEST
			screening || rejected -> ApplicationStage.SCREENING
			else -> ApplicationStage.APPLIED
		}
		val result = when {
			rejected -> ApplicationResult.REJECTED
			offered -> ApplicationResult.OFFERED
			else -> ApplicationResult.ACTIVE
		}
		val schedule = extractSchedule(candidate)
		val confidence = confidence(candidate, stage, schedule != null)
		return AnalyzedMailCandidate(
			sourceSummary = summarize(candidate),
			company = extractCompany(candidate),
			position = extractPosition(semanticSubject),
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

	private fun maskUrls(value: String): String =
		URL.replace(value) { match -> " ".repeat(match.value.length) }

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

	private fun extractSchedule(candidate: MailCandidate): ExtractedSchedule? {
		val rawContent = "${candidate.subject}\n${candidate.textPreview}"
		val content = maskUrls(rawContent)
		val dateMatches = DATE.findAll(content).toList()
		if (dateMatches.isEmpty()) return null
		if (TIMEZONE_IN_CONTENT.containsMatchIn(content)) return null
		val dates = dateMatches.map { match ->
			val year = match.groups["year"]?.value?.toIntOrNull() ?: return null
			val month = match.groups["month"]?.value?.toIntOrNull() ?: return null
			val day = match.groups["day"]?.value?.toIntOrNull() ?: return null
			runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
		}
		if (dates.distinct().size > 1) return null
		val date = dates.first()
		val temporalSegments = buildList {
			val leadingContent = content.substring(0, dateMatches.first().range.first)
			if (leadingContent.isNotBlank()) add(TemporalSegment(leadingContent, false))
			dateMatches.forEachIndexed { index, match ->
				val remainderStart = match.range.last + 1
				val remainderEnd = dateMatches.getOrNull(index + 1)?.range?.first ?: content.length
				add(TemporalSegment(content.substring(remainderStart, remainderEnd), true))
			}
		}
		val occurrences = temporalSegments.map { segment ->
			val remainder = segment.content
			val explicitTimeCandidates = findExplicitTimeCandidates(remainder)
			val explicitTimes = explicitTimeCandidates.filter { candidateTime ->
				isScheduleTimeContext(remainder, candidateTime)
			}
			val rejectedExplicitTimes = explicitTimeCandidates.filterNot(explicitTimes::contains)
			val adjacentBareTime = if (segment.allowAdjacentBareTime) {
				ADJACENT_BARE_TIME.find(remainder)?.takeUnless { bareTime ->
					explicitTimeCandidates.any { explicitTime ->
						explicitTime.range.first <= bareTime.range.last &&
							bareTime.range.first <= explicitTime.range.last
					}
				}
			} else {
				null
			}
			ScheduleOccurrence(
				remainder,
				explicitTimes,
				adjacentBareTime,
				MALFORMED_TIME.find(remainder),
				rejectedExplicitTimes.isNotEmpty(),
				rejectedExplicitTimes.any { candidateTime ->
					!isExplicitlyUnrelatedTimeContext(remainder, candidateTime) &&
						!hasUnsupportedTimeSyntax(remainder, candidateTime)
				},
				explicitTimeCandidates.any { candidateTime ->
					hasUnsupportedTimeSyntax(remainder, candidateTime)
				},
			)
		}
		val normalizedCandidateTimes = occurrences.flatMap { occurrence ->
			buildList {
				occurrence.explicitTimes.forEach { match ->
					add(parseExplicitTime(occurrence.remainder, match) ?: return null)
				}
				occurrence.adjacentBareTime?.let { match ->
					add(parseAdjacentTime(match) ?: return null)
				}
			}
		}
		if (normalizedCandidateTimes.distinct().size > 1) return null
		if (occurrences.any(ScheduleOccurrence::hasUnsupportedTimeSyntax)) return null
		if (occurrences.any { it.malformedTime != null }) return null
		if (occurrences.any(ScheduleOccurrence::hasAmbiguousRejectedExplicitTime)) return null
		if (normalizedCandidateTimes.isEmpty() && occurrences.any(ScheduleOccurrence::hasRejectedExplicitTime)) {
			return null
		}
		val occurrence = occurrences.firstOrNull { selected ->
			selected.explicitTimes.isNotEmpty() || selected.adjacentBareTime != null || selected.malformedTime != null
		} ?: occurrences.first()
		val remainder = occurrence.remainder
		val explicitTime = occurrence.explicitTimes.firstOrNull()
		val adjacentBareTime = occurrence.adjacentBareTime
		val timeMatch = explicitTime ?: adjacentBareTime
		val explicitLocalTime = explicitTime?.let { parseExplicitTime(remainder, it) ?: return null }
		val adjacentLocalTime = adjacentBareTime?.let { parseAdjacentTime(it) ?: return null }
		val hour = explicitLocalTime?.hour
			?: adjacentLocalTime?.hour
			?: if (timeMatch == null) 9 else return null
		val minute = explicitLocalTime?.minute ?: adjacentLocalTime?.minute ?: 0
		val startsAt = runCatching {
			LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(SEOUL).toInstant()
		}.getOrNull() ?: return null
		val scheduleTypes = scheduleTypes(content)
		if (scheduleTypes.size > 1) return null
		val type = scheduleTypes.singleOrNull() ?: ScheduleType.OTHER
		val action = when (type) {
			ScheduleType.INTERVIEW -> "면접"
			ScheduleType.TEST -> "채용 과제 또는 테스트"
			ScheduleType.APPLICATION -> "지원 마감"
			else -> "채용 일정 확인"
		}
		return ExtractedSchedule(type, action, startsAt, startsAt.plusSeconds(3600))
	}

	private fun findExplicitTimeCandidates(value: String): List<MatchResult> =
		TIME_CANDIDATE.findAll(value).filter { candidateTime ->
			candidateTime.groups["colonMinute"] != null || candidateTime.groups["koreanHourUnit"] != null ||
				candidateTime.groups["meridiemPrefix"] != null || candidateTime.groups["meridiemSuffix"] != null
		}.toList()

	private fun isScheduleTimeContext(remainder: String, match: MatchResult): Boolean {
		if (hasUnsupportedTimeSyntax(remainder, match)) return false
		if (isExplicitlyUnrelatedTimeContext(remainder, match)) return false
		val prefix = remainder.substring(0, match.range.first)
		if (prefix.all(Char::isWhitespace)) return true
		val previousBoundary = remainder.lastIndexOfAny(SCHEDULE_SENTENCE_BOUNDARIES, match.range.first - 1)
		if (previousBoundary >= 0 && remainder[previousBoundary] == '\n' &&
			remainder.substring(previousBoundary + 1, match.range.first).all(Char::isWhitespace)
		) return true
		val firstBoundary = remainder.indexOfAny(SCHEDULE_SENTENCE_BOUNDARIES)
		if (firstBoundary == -1 || match.range.first < firstBoundary) return true
		val sentenceStart = remainder.lastIndexOfAny(SCHEDULE_SENTENCE_BOUNDARIES, match.range.first - 1) + 1
		val nextBoundary = remainder.indexOfAny(SCHEDULE_SENTENCE_BOUNDARIES, match.range.last + 1)
		val sentenceEnd = if (nextBoundary == -1) remainder.length else nextBoundary
		val sentence = remainder.substring(sentenceStart, sentenceEnd).lowercase(Locale.ROOT)
		return SCHEDULE_TIME_CUES.any(sentence::contains)
	}

	private fun isExplicitlyUnrelatedTimeContext(remainder: String, match: MatchResult): Boolean {
		val clauseStart = remainder.lastIndexOfAny(TIME_CLAUSE_BOUNDARIES, match.range.first - 1) + 1
		val nextClauseBoundary = remainder.indexOfAny(TIME_CLAUSE_BOUNDARIES, match.range.last + 1)
		val clauseEnd = if (nextClauseBoundary == -1) remainder.length else nextClauseBoundary
		val clause = remainder.substring(clauseStart, clauseEnd).lowercase(Locale.ROOT)
		val matchStart = match.range.first - clauseStart
		val matchEndExclusive = match.range.last + 1 - clauseStart
		val unrelatedDistance = nearestCueDistance(clause, matchStart, matchEndExclusive, UNRELATED_TIME_CUES)
			?: return false
		val scheduleDistance = nearestCueDistance(clause, matchStart, matchEndExclusive, SCHEDULE_TIME_CUES)
		return scheduleDistance == null || unrelatedDistance <= scheduleDistance
	}

	private fun lastCueIndex(value: String, cues: Set<String>): Int =
		cues.maxOfOrNull { cue -> value.lastIndexOf(cue) } ?: -1

	private fun nearestCueDistance(
		value: String,
		matchStart: Int,
		matchEndExclusive: Int,
		cues: Set<String>,
	): Int? = cues.flatMap { cue ->
		buildList {
			val precedingIndex = value.substring(0, matchStart).lastIndexOf(cue)
			if (precedingIndex >= 0) add(matchStart - (precedingIndex + cue.length))
			val followingIndex = value.substring(matchEndExclusive).substringBefore('\n').indexOf(cue)
			if (followingIndex >= 0) add(followingIndex)
		}
	}.minOrNull()

	private fun hasUnsupportedTimeSyntax(remainder: String, match: MatchResult): Boolean =
		TIMEZONE_PREFIX.containsMatchIn(remainder.substring(0, match.range.first)) ||
			TIMEZONE_SUFFIX.containsMatchIn(remainder.substring(match.range.last + 1)) ||
			TIME_SECONDS_SUFFIX.containsMatchIn(remainder.substring(match.range.last + 1)) ||
			MALFORMED_KOREAN_MINUTE_SUFFIX.containsMatchIn(remainder.substring(match.range.last + 1))

	private fun parseExplicitTime(remainder: String, match: MatchResult): LocalTime? {
		val rawHour = match.groups["hour"]?.value?.toIntOrNull() ?: return null
		val attachedMeridiems = listOfNotNull(
			match.groups["meridiemPrefix"]?.value?.let(::normalizeMeridiem),
			match.groups["meridiemSuffix"]?.value?.let(::normalizeMeridiem),
		).distinct()
		if (attachedMeridiems.size > 1) return null
		val meridiem = attachedMeridiems.singleOrNull()
		if (meridiem != null) {
			val clauseStart = remainder.lastIndexOfAny(TIME_CLAUSE_BOUNDARIES, match.range.first - 1) + 1
			val clausePrefix = remainder.substring(clauseStart, match.range.first).lowercase(Locale.ROOT)
			val nearestCue = maxOf(
				lastCueIndex(clausePrefix, UNRELATED_TIME_CUES),
				lastCueIndex(clausePrefix, SCHEDULE_TIME_CUES),
			)
			val previousExplicitTimeEnd = findExplicitTimeCandidates(
				remainder.substring(clauseStart, match.range.first),
			).lastOrNull()?.range?.last?.let { previousEnd -> clauseStart + previousEnd + 1 } ?: clauseStart
			val nearestCueStart = if (nearestCue >= 0) clauseStart + nearestCue else clauseStart
			val meridiemScopeStart = maxOf(previousExplicitTimeEnd, nearestCueStart)
			val precedingMeridiems = KOREAN_MERIDIEM_TOKEN
				.findAll(remainder.substring(meridiemScopeStart, match.range.first))
				.mapNotNull { normalizeMeridiem(it.value) }
			if (precedingMeridiems.any { it != meridiem }) return null
		}
		val hour = normalizeHour(rawHour, meridiem) ?: return null
		val minute = match.groups["colonMinute"]?.value?.toIntOrNull()
			?: match.groups["koreanMinute"]?.value?.toIntOrNull()
			?: if (match.groups["koreanHalf"] != null) 30 else 0
		return runCatching { LocalTime.of(hour, minute) }.getOrNull()
	}

	private fun parseAdjacentTime(match: MatchResult): LocalTime? {
		val rawHour = match.groups["hour"]?.value?.toIntOrNull() ?: return null
		val hour = normalizeHour(rawHour, null) ?: return null
		return LocalTime.of(hour, 0)
	}

	private fun scheduleTypes(value: String): Set<ScheduleType> {
		val normalized = value.lowercase(Locale.ROOT)
		return buildSet {
			if (INTERVIEW_KEYWORDS.any(normalized::contains)) add(ScheduleType.INTERVIEW)
			if (TEST_KEYWORDS.any(normalized::contains)) add(ScheduleType.TEST)
			if (DEADLINE_KEYWORDS.any(normalized::contains)) add(ScheduleType.APPLICATION)
		}
	}

	private fun normalizeMeridiem(value: String): String? = when (value.lowercase(Locale.ROOT)) {
		"오전", "am" -> "am"
		"오후", "pm" -> "pm"
		else -> null
	}

	private fun normalizeHour(hour: Int, meridiem: String?): Int? = when (meridiem) {
		null -> hour.takeIf { it in 0..23 }
		"am" -> when (hour) {
			12 -> 0
			in 1..11 -> hour
			else -> null
		}
		"pm" -> when (hour) {
			12 -> 12
			in 1..11 -> hour + 12
			else -> null
		}
		else -> null
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

	private data class ScheduleOccurrence(
		val remainder: String,
		val explicitTimes: List<MatchResult>,
		val adjacentBareTime: MatchResult?,
		val malformedTime: MatchResult?,
		val hasRejectedExplicitTime: Boolean,
		val hasAmbiguousRejectedExplicitTime: Boolean,
		val hasUnsupportedTimeSyntax: Boolean,
	)

	private data class TemporalSegment(
		val content: String,
		val allowAdjacentBareTime: Boolean,
	)

	private companion object {
		val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		val WHITESPACE = Regex("\\s+")
		val BRACKET_COMPANY = Regex("^[\\s\\[\\(【]+([^\\]\\)】]{2,160})[\\]\\)】]")
		val LEADING_BRACKET = Regex("^[\\s\\[\\(【]+[^\\]\\)】]{2,160}[\\]\\)】]")
		val EMAIL_DOMAIN = Regex("@([A-Za-z0-9.-]+)")
		val URL = Regex(
			"(?i)(?:\\bhttps?://[^\\s<>]+|\\bwww\\.[^\\s<>]+|" +
				"(?<![@\\p{L}\\d_-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
				"[a-z]{2,63}(?::\\d{1,5})?(?:/[^\\s<>]*)?)",
		)
		val DATE = Regex(
			"(?<!\\d)(?<year>20\\d{2})[년./-]\\s*(?<month>\\d{1,2})[월./-]\\s*" +
				"(?<day>\\d{1,2})(?:일(?!\\d)|(?![일\\d]))",
		)
		val TIME_CANDIDATE = Regex(
			"(?<![\\p{L}\\d])(?:T\\s*)?" +
				"(?:(?<meridiemPrefix>(?i:am|pm)|오전|오후)\\s*)?" +
				"(?<hour>\\d{1,2})(?!\\d)" +
				"(?=(?::|시(?!간)|\\s*(?:(?i:am|pm)(?![\\p{L}\\d])|오전|오후|$|[,.!?;)}\\]])))" +
				"(?::(?<colonMinute>\\d{2})(?!\\d)|(?<koreanHourUnit>시)(?!간)" +
					"(?:\\s*(?:(?<koreanMinute>\\d{1,2})(?!\\d)분?|(?<koreanHalf>반)))?)?" +
			"(?:\\s*(?<meridiemSuffix>(?i:am|pm)|오전|오후)(?![\\p{L}\\d])" +
				"(?!\\s*\\d{1,2}\\s*(?::|시)))?",
		)
		val ADJACENT_BARE_TIME = Regex(
			"^[\\sT,;:()\\[\\]{}<>/\\\\-]*(?<hour>\\d{1,2})(?![\\p{L}\\d:])",
		)
		val MALFORMED_TIME = Regex(
			"(?<![\\p{L}\\d])\\d{1,2}:(?:\\d(?!\\d)|\\d{3,}(?!\\d)|(?!\\d))",
		)
		const val TIMEZONE_TOKEN =
			"(?:(?i:z|utc|gmt|kst)(?![\\p{L}\\d_])|[+-]\\d{2}:?\\d{2}(?!\\d)|" +
				"(?i:Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Brazil|Canada|Chile|Etc|Europe|" +
				"Indian|Mexico|Pacific|US)/[A-Za-z0-9_+-]+(?:/[A-Za-z0-9_+-]+)?)"
		const val TIMEZONE_ABBREVIATION =
			"(?:(?i:est|edt|cst|cdt|mst|mdt|pst|pdt|hkt|sgt|jst|bst|ist|cet|cest|eet|eest|aest|aedt|" +
				"acst|acdt|nzst|nzdt)(?![\\p{L}\\d_])|[A-Z]{2,5}(?![\\p{L}\\d_]))"
		const val TIMEZONE_ADJACENT_TOKEN =
			"(?:$TIMEZONE_TOKEN|$TIMEZONE_ABBREVIATION|[+-]\\d{2}(?![\\d:]))"
		const val TIMEZONE_GLOBAL_ABBREVIATION =
			"(?i:est|edt|cst|cdt|mst|mdt|pst|pdt|hkt|sgt|jst|bst|ist|cet|cest|eet|eest|" +
				"aest|aedt|acst|acdt|nzst|nzdt)(?![\\p{L}\\d_])"
		val TIMEZONE_IN_CONTENT = Regex(
			"(?<![\\p{L}\\d_])(?:$TIMEZONE_TOKEN|$TIMEZONE_GLOBAL_ABBREVIATION)",
		)
		val TIMEZONE_PREFIX = Regex(
			"(?<![\\p{L}\\d_])$TIMEZONE_ADJACENT_TOKEN[\\s,;:()\\[\\]{}<>]*$",
		)
		val TIMEZONE_SUFFIX = Regex(
			"^(?:\\s*:\\d{2}(?:\\.\\d+)?)?[\\s,;:()\\[\\]{}<>]*$TIMEZONE_ADJACENT_TOKEN",
		)
		val TIME_SECONDS_SUFFIX = Regex(
			"^(?:\\s*:\\d{2}|\\s*\\d+\\s*(?:초|(?i:seconds?|secs?)(?![\\p{L}\\d_])))",
		)
		val MALFORMED_KOREAN_MINUTE_SUFFIX = Regex("^\\s*\\d{3,}\\s*분")
		val KOREAN_MERIDIEM_TOKEN = Regex("오전|오후")
		val SCHEDULE_SENTENCE_BOUNDARIES = charArrayOf('\n', '.', '!', '?', '。')
		val TIME_CLAUSE_BOUNDARIES = charArrayOf('.', '!', '?', '。', ',', ';')
		val UNRELATED_TIME_CUES = setOf(
			"문의", "문의 가능", "문의가능", "문의 시간", "문의시간", "문의 가능 시간", "문의가능시간",
			"연락", "연락 가능", "연락가능", "연락 시간", "연락시간", "연락 가능 시간", "연락가능시간",
			"상담", "상담 가능", "상담가능", "상담 시간", "상담시간", "상담 가능 시간", "상담가능시간",
			"소요 시간", "소요시간", "예상 소요", "예상소요",
			"contact hours", "office hours", "available for questions", "duration", "estimated length",
		)
		val SCHEDULE_TIME_CUES = setOf(
			"시작", "개시", "면접", "인터뷰", "면접 시간", "면접시간", "인터뷰 시간", "인터뷰시간",
			"테스트 시간", "테스트시간", "시험 시간", "시험시간", "예정 시간", "예정시간",
			"변경", "수정", "확정", "조정", "재조정",
			"새 시간", "새시간",
			"start time", "starts at", "begin time", "begins at", "interview", "interview time", "test time",
			"assessment time", "scheduled at", "changed", "updated", "rescheduled", "confirmed", "adjusted",
			"new time",
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
