package com.meenseek.jobvis.imports

import com.meenseek.jobvis.common.BadRequestException
import java.util.Locale

enum class ImportRequestedBy { USER, MONITOR }
enum class ImportRunStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
enum class ImportDraftStatus { PENDING, ACCEPTED, REJECTED }

fun ImportDraftStatus.apiValue(): String = name.lowercase(Locale.ROOT)

fun parseDraftStatus(value: String?): ImportDraftStatus? = value?.takeIf(String::isNotBlank)?.let { raw ->
	ImportDraftStatus.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
		?: throw BadRequestException("검토 초안 상태가 올바르지 않습니다.")
}
