package com.meenseek.jobvis.auth

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

interface CurrentUserProvider {
	fun currentUserId(request: HttpServletRequest): UUID
}
