package net.corda.notary.jpa

import net.corda.core.flows.FlowSession
import net.corda.core.internal.notary.AsyncCFTNotaryService
import net.corda.core.internal.notary.NotaryServiceFlow
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.services.transactions.NonValidatingNotaryFlow
import net.corda.node.services.transactions.ValidatingNotaryFlow
import net.corda.nodeapi.internal.config.parseAs
import java.security.PublicKey

/** Notary service backed by a replicated MySQL database. */
class JPANotaryService(
        override val services: ServiceHubInternal,
        override val notaryIdentityKey: PublicKey) : AsyncCFTNotaryService() {

    private val notaryConfig = services.configuration.notary
            ?: throw IllegalArgumentException("Failed to register ${this::class.java}: notary configuration not present")

    override val asyncUniquenessProvider = with(services) {
        val jpaNotaryConfig = try {
            notaryConfig.extraConfig!!.parseAs<JPANotaryConfiguration>()
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to register ${JPANotaryService::class.java}: JPA notary configuration not present")
        }
        JPAUniquenessProvider(services.clock, services.database, jpaNotaryConfig)
    }

    override fun createServiceFlow(otherPartySession: FlowSession): NotaryServiceFlow {
        return if (notaryConfig.validating) {
            ValidatingNotaryFlow(otherPartySession, this)
        } else NonValidatingNotaryFlow(otherPartySession, this)
    }

    override fun start() {
    }

    override fun stop() {
        asyncUniquenessProvider.stop()
    }
}
