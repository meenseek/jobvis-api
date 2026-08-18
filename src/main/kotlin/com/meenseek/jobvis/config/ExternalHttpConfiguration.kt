package com.meenseek.jobvis.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Configuration
class ExternalHttpConfiguration(
	@Value("\${jobvis.external-http.connect-timeout:PT5S}") private val connectTimeout: Duration,
	@Value("\${jobvis.external-http.read-timeout:PT30S}") private val readTimeout: Duration,
) {
	init {
		require(!connectTimeout.isZero && !connectTimeout.isNegative) {
			"jobvis.external-http.connect-timeout은 양수여야 합니다."
		}
		require(!readTimeout.isZero && !readTimeout.isNegative) {
			"jobvis.external-http.read-timeout은 양수여야 합니다."
		}
	}

	@Bean
	fun externalRequestFactory(): SimpleClientHttpRequestFactory = SimpleClientHttpRequestFactory().apply {
		setConnectTimeout(connectTimeout)
		setReadTimeout(readTimeout)
	}

	@Bean
	@Qualifier("externalRestClient")
	fun externalRestClient(externalRequestFactory: SimpleClientHttpRequestFactory): RestClient =
		RestClient.builder().requestFactory(externalRequestFactory).build()

	@Bean
	@Qualifier("externalRestOperations")
	fun externalRestOperations(externalRequestFactory: SimpleClientHttpRequestFactory): RestOperations =
		RestTemplate(externalRequestFactory)
}
