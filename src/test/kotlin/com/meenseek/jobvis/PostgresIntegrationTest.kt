package com.meenseek.jobvis

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers
abstract class PostgresIntegrationTest {
	companion object {
		@Container
		@ServiceConnection
		@JvmField
		val postgres: PostgreSQLContainer =
			PostgreSQLContainer(DockerImageName.parse("postgres:18.4-alpine"))
	}
}
