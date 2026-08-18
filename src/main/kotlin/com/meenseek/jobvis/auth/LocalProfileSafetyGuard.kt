package com.meenseek.jobvis.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.InetAddress

@Component
@Profile("local")
class LocalProfileSafetyGuard(
	@Value("\${server.address}") serverAddress: String,
) {
	init {
		check(runCatching { InetAddress.getByName(serverAddress).isLoopbackAddress }.getOrDefault(false)) {
			"local 프로필의 server.address는 loopback 주소여야 합니다."
		}
	}
}
