package net.corda.node

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.PermissionException
import net.corda.core.context.AuthServiceId
import net.corda.core.context.InvocationContext
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Issued
import net.corda.core.crypto.isFulfilledBy
import net.corda.core.crypto.keys
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.Party
import net.corda.core.internal.RPC_UPLOADER
import net.corda.core.internal.uncheckedCast
import net.corda.core.messaging.*
import net.corda.core.utilities.NonEmptySet
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.finance.DOLLARS
import net.corda.finance.GBP
import net.corda.node.internal.security.AuthorizingSubject
import net.corda.node.internal.security.RPCSecurityManagerImpl
import net.corda.node.services.Permissions.Companion.all
import net.corda.node.services.Permissions.Companion.invokeRpc
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.node.services.rpc.CURRENT_RPC_CONTEXT
import net.corda.node.services.rpc.RpcAuthContext
import net.corda.nodeapi.exceptions.NonRpcFlowException
import net.corda.nodeapi.internal.config.User
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.expect
import net.corda.testing.core.expectEvents
import net.corda.testing.core.sequence
import net.corda.testing.internal.fromUserList
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.testActor
import org.apache.commons.io.IOUtils
import org.assertj.core.api.Assertions.*
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import rx.Observable
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Mock an AuthorizingSubject instance sticking to a fixed set of permissions
private fun buildSubject(principal: String, permissionStrings: Set<String>): AuthorizingSubject {
    return RPCSecurityManagerImpl.fromUserList(
            id = AuthServiceId("TEST"),
            users = listOf(User(
                    username = principal,
                    password = "",
                    permissions = permissionStrings
            ))
    ).buildSubject(principal)
}

class CordaRPCOpsImplTest {
    private companion object {
        const val testJar = "net/corda/node/testing/test.jar"
    }

    private lateinit var mockNet: InternalMockNetwork
    private lateinit var aliceNode: TestStartedNode
    private lateinit var alice: Party
//    private lateinit var notary: Party
    private lateinit var rpc: CordaRPCOps
    private lateinit var stateMachineUpdates: Observable<StateMachineUpdate>
//    private lateinit var transactions: Observable<SignedTransaction>

    @Before
    fun setup() {
        mockNet = InternalMockNetwork(cordappsForAllNodes = FINANCE_CORDAPPS)
        aliceNode = mockNet.createNode(InternalMockNodeParameters(legalName = ALICE_NAME))
        rpc = aliceNode.rpcOps
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))

        mockNet.runNetwork()
//        withPermissions(invokeRpc(CordaRPCOps::notaryIdentities)) {
//            notary = rpc.notaryIdentities().single()
//        }
        alice = aliceNode.services.myInfo.identityFromX500Name(ALICE_NAME)
    }

    @After
    fun cleanUp() {
        if (::mockNet.isInitialized) {
            mockNet.stopNodes()
        }
    }

    @Test(timeout=300_000)
	fun `can upload an attachment`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(invokeRpc(CordaRPCOps::uploadAttachment), invokeRpc(CordaRPCOps::attachmentExists)) {
            val inputJar = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
            val secureHash = rpc.uploadAttachment(inputJar)
            assertTrue(rpc.attachmentExists(secureHash))
        }
    }

    @Test(timeout=300_000)
	fun `cannot upload the same attachment`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(invokeRpc(CordaRPCOps::uploadAttachment), invokeRpc(CordaRPCOps::attachmentExists)) {
            val inputJar1 = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
            val inputJar2 = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
            rpc.uploadAttachment(inputJar1)
            assertThatExceptionOfType(java.nio.file.FileAlreadyExistsException::class.java).isThrownBy {
                rpc.uploadAttachment(inputJar2)
            }
        }
    }

    @Test(timeout=300_000)
	fun `can download an uploaded attachment`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(invokeRpc(CordaRPCOps::uploadAttachment), invokeRpc(CordaRPCOps::openAttachment)) {
            val inputJar = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
            val secureHash = rpc.uploadAttachment(inputJar)
            val bufferFile = ByteArrayOutputStream()
            val bufferRpc = ByteArrayOutputStream()

            IOUtils.copy(Thread.currentThread().contextClassLoader.getResourceAsStream(testJar), bufferFile)
            IOUtils.copy(rpc.openAttachment(secureHash), bufferRpc)

            assertArrayEquals(bufferFile.toByteArray(), bufferRpc.toByteArray())
        }
    }

    @Test(timeout=300_000)
	fun `can upload attachment with metadata`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(invokeRpc(CordaRPCOps::uploadAttachmentWithMetadata), invokeRpc(CordaRPCOps::attachmentExists)) {
            val inputJar = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
            val secureHash = rpc.uploadAttachmentWithMetadata(inputJar, "Iron Fist", "Season 2")
            assertTrue(rpc.attachmentExists(secureHash))
        }
    }

//    @Test(timeout=300_000)
//	fun `attachment uploaded with metadata has specified filename`() {
//        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
//        withPermissions(invokeRpc(CordaRPCOps::uploadAttachmentWithMetadata), invokeRpc(CordaRPCOps::queryAttachments)) {
//            val inputJar = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
//            rpc.uploadAttachmentWithMetadata(inputJar, "The Punisher", "Season 1")
//            assertEquals(
//                rpc.queryAttachments(
//                    AttachmentQueryCriteria.AttachmentsQueryCriteria(
//                        filenameCondition = ColumnPredicate.EqualityComparison(
//                            EqualityComparisonOperator.EQUAL,
//                            "Season 1"
//                        )
//                    ), null
//                ).size, 1
//            )
//        }
//    }

    @Test(timeout=300_000)
	fun `attachment uploaded with metadata can be from a privileged user`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(invokeRpc(CordaRPCOps::uploadAttachmentWithMetadata), invokeRpc(CordaRPCOps::attachmentExists)) {
            val inputJar = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
            val secureHash = rpc.uploadAttachmentWithMetadata(inputJar, RPC_UPLOADER, "Season 1")
            assertTrue(rpc.attachmentExists(secureHash))
        }
    }

//    @Test(timeout=300_000)
//	fun `attachment uploaded with metadata has specified uploader`() {
//        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
//        withPermissions(invokeRpc(CordaRPCOps::uploadAttachmentWithMetadata), invokeRpc(CordaRPCOps::queryAttachments)) {
//            val inputJar = Thread.currentThread().contextClassLoader.getResourceAsStream(testJar)
//            rpc.uploadAttachmentWithMetadata(inputJar, "Daredevil", "Season 3")
//            assertEquals(
//                rpc.queryAttachments(
//                    AttachmentQueryCriteria.AttachmentsQueryCriteria(
//                        uploaderCondition = ColumnPredicate.EqualityComparison(
//                            EqualityComparisonOperator.EQUAL,
//                            "Daredevil"
//                        )
//                    ), null
//                ).size, 1
//            )
//        }
//    }

    @Test(timeout=300_000)
	fun `attempt to start non-RPC flow`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(startFlow<NonRPCFlow>()) {
            assertThatExceptionOfType(NonRpcFlowException::class.java).isThrownBy {
                rpc.startFlow(::NonRPCFlow)
            }
        }
    }

    @Test(timeout=300_000)
	fun `kill a stuck flow through RPC`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(
                startFlow<NewJoinerFlow>(),
                invokeRpc(CordaRPCOps::killFlow),
                invokeRpc(CordaRPCOps::stateMachinesFeed),
                invokeRpc(CordaRPCOps::stateMachinesSnapshot)
        ) {
            val flow = rpc.startFlow(::NewJoinerFlow)
            val killed = rpc.killFlow(flow.id)
            assertThat(killed).isTrue()
            assertThat(rpc.stateMachinesSnapshot().map { info -> info.id }).doesNotContain(flow.id)
        }
    }

    @Test(timeout=300_000)
	fun `kill a waiting flow through RPC`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(
                startFlow<HopefulFlow>(),
                invokeRpc(CordaRPCOps::killFlow),
                invokeRpc(CordaRPCOps::stateMachinesFeed),
                invokeRpc(CordaRPCOps::stateMachinesSnapshot)
        ) {
            val flow = rpc.startFlow(::HopefulFlow, alice)
            val killed = rpc.killFlow(flow.id)
            assertThat(killed).isTrue()
            assertThat(rpc.stateMachinesSnapshot().map { info -> info.id }).doesNotContain(flow.id)
        }
    }

//    @StartableByRPC
//    class SoftLock(private val stateRef: StateRef, private val duration: Duration) : FlowLogic<Unit>() {
//        @Suspendable
//        override fun call() {
//            logger.info("Soft locking state with hash $stateRef...")
//            serviceHub.vaultService.softLockReserve(runId.uuid, NonEmptySet.of(stateRef))
//            sleep(duration)
//        }
//    }

    @Test(timeout=300_000)
	fun `kill a nonexistent flow through RPC`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(invokeRpc(CordaRPCOps::killFlow)) {
            val nonexistentFlowId = StateMachineRunId.createRandom()
            val killed = rpc.killFlow(nonexistentFlowId)
            assertThat(killed).isFalse()
        }
    }

    @Test(timeout=300_000)
    fun `attempt to start RPC flow with void return`() {
        CURRENT_RPC_CONTEXT.set(RpcAuthContext(InvocationContext.rpc(testActor()), buildSubject("TEST_USER", emptySet())))
        withPermissions(startFlow<VoidRPCFlow>()) {
            val result = rpc.startFlow(::VoidRPCFlow)
            mockNet.runNetwork()
            assertNull(result.returnValue.getOrThrow())
        }
    }

    @StartableByRPC
    class NewJoinerFlow : FlowLogic<String>() {
        @Suspendable
        override fun call(): String {
            logger.info("When can I join you say? Almost there buddy...")
            Fiber.currentFiber().join()
            return "You'll never get me!"
        }
    }

    @StartableByRPC
    class HopefulFlow(private val party: Party) : FlowLogic<String>() {
        @Suspendable
        override fun call(): String {
            logger.info("Waiting for a miracle...")
            return initiateFlow(party).receive<String>().unwrap { it }
        }
    }

    class NonRPCFlow : FlowLogic<Unit>() {
        @Suspendable
        override fun call() = Unit
    }



    @StartableByRPC
    class VoidRPCFlow : FlowLogic<Void?>() {
        @Suspendable
        override fun call(): Void? = null
    }

    private inline fun withPermissions(vararg permissions: String, action: () -> Unit) {
        val previous = CURRENT_RPC_CONTEXT.get()
        try {
            CURRENT_RPC_CONTEXT.set(previous.copy(authorizer = buildSubject(previous.principal, permissions.toSet())))
            action.invoke()
        } finally {
            CURRENT_RPC_CONTEXT.set(previous)
        }
    }

    private inline fun withoutAnyPermissions(action: () -> Unit) = withPermissions(action = action)
}
