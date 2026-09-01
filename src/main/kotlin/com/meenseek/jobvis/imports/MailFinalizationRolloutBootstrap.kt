package com.meenseek.jobvis.imports

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["jobvis.import.auto-complete-rollout"], havingValue = "true")
class MailFinalizationRolloutBootstrap(
	private val rolloutService: MailFinalizationRolloutService,
) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		rolloutService.reconcileAndCompleteIfReady()
	}
}
