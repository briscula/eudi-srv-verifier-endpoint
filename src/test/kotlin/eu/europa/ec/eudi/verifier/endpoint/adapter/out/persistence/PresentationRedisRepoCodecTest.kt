/*
 * Copyright (c) 2023-2026 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.verifier.endpoint.adapter.out.persistence

import eu.europa.ec.eudi.verifier.endpoint.TestContext
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.json.jsonSupport
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils.getOrThrow
import eu.europa.ec.eudi.verifier.endpoint.domain.*
import eu.europa.ec.eudi.verifier.endpoint.port.input.WalletResponseAcceptedTO
import eu.europa.ec.eudi.verifier.endpoint.port.input.WalletResponseTO
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.PresentationEvent
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class PresentationRedisRepoCodecTest {

    @Test
    fun `request object retrieved storage keeps original request uri method`() {
        val requested = requestedPresentation(requestUriMethod = RequestUriMethod.Post)
        val requestedStored = requested.toStored(previous = null)

        val retrieved = requested.retrieveRequestObject(TestContext.testClock).getOrThrow()
        val retrievedStored = retrieved.toStored(previous = requestedStored)

        assertEquals(RequestUriMethod.Post, retrievedStored.requestUriMethod)
    }

    @Test
    fun `submitted presentation survives redis codec round trip`() {
        val requested = requestedPresentation()
        val requestedStored = requested.toStored(previous = null)
        val retrieved = requested.retrieveRequestObject(TestContext.testClock).getOrThrow()
        val retrievedStored = retrieved.toStored(previous = requestedStored)
        val submitted = retrieved.submit(
            clock = TestContext.testClock,
            walletResponse = WalletResponse.Error("access_denied", "wallet rejected request"),
            responseCode = ResponseCode("code-123"),
        ).getOrThrow()

        val roundTripped = submitted.redisRoundTrip(previous = retrievedStored)

        val restored = assertIs<Presentation.Submitted>(roundTripped)
        assertEquals(submitted.id, restored.id)
        assertEquals(submitted.requestId, restored.requestId)
        assertEquals("code-123", restored.responseCode?.value)
        val walletResponse = assertIs<WalletResponse.Error>(restored.walletResponse)
        assertEquals("access_denied", walletResponse.value)
        assertEquals("wallet rejected request", walletResponse.description)
    }

    @Test
    fun `timed out presentation survives redis codec round trip`() {
        val requested = requestedPresentation()
        val requestedStored = requested.toStored(previous = null)
        val retrieved = requested.retrieveRequestObject(TestContext.testClock).getOrThrow()
        val retrievedStored = retrieved.toStored(previous = requestedStored)
        val submitted = retrieved.submit(
            clock = TestContext.testClock,
            walletResponse = WalletResponse.Error("access_denied", null),
            responseCode = ResponseCode("code-123"),
        ).getOrThrow()
        val submittedStored = submitted.toStored(previous = retrievedStored)
        val timeoutClock = Clock.fixed(TestContext.testClock.now() + 1.seconds, TimeZone.UTC)
        val timedOut = submitted.timedOut(timeoutClock).getOrThrow()

        val roundTripped = timedOut.redisRoundTrip(previous = submittedStored)

        assertIs<Presentation.TimedOut>(roundTripped)
        val restored = roundTripped
        assertEquals(timedOut.id, restored.id)
        assertEquals(timedOut.initiatedAt, restored.initiatedAt)
        assertEquals(timedOut.requestObjectRetrievedAt, restored.requestObjectRetrievedAt)
        assertEquals(timedOut.submittedAt, restored.submittedAt)
        assertEquals(timedOut.timedOutAt, restored.timedOutAt)
    }

    @Test
    fun `event payload record keeps rendered api shape`() {
        val event = PresentationEvent.WalletResponsePosted(
            transactionId = TransactionId("tx-1"),
            timestamp = TestContext.testClock.now(),
            walletResponse = WalletResponseTO(error = "access_denied", errorDescription = "wallet rejected request"),
            verifierEndpointResponse = WalletResponseAcceptedTO("https://verifier.example/callback?response_code=abc"),
        )

        val record = event.toRecord()
        val stored = StoredPresentationEventRecord.from(record)
        val restored = stored.toRecord()

        assertEquals(record.timestamp, restored.timestamp)
        assertEquals("Wallet response posted", restored.payload["event"]?.toString()?.trim('"'))
        assertEquals("Wallet", restored.payload["actor"]?.toString()?.trim('"'))
        assertNotNull(restored.payload["wallet_response"])
    }

    private fun requestedPresentation(
        requestUriMethod: RequestUriMethod = RequestUriMethod.Get,
    ) = Presentation.Requested(
        id = TransactionId("tx-1"),
        initiatedAt = TestContext.testClock.now(),
        query = sampleQuery(),
        transactionData = null,
        requestId = RequestId("req-1"),
        requestUriMethod = requestUriMethod,
        nonce = Nonce("nonce-1"),
        responseMode = ResponseMode.DirectPost,
        getWalletResponseMethod = GetWalletResponseMethod.Poll,
        issuerChain = null,
        profile = Profile.OpenId4VP,
    )

    private fun sampleQuery() = DCQL(
        credentials = Credentials(
            CredentialQuery.mdoc(
                id = QueryId("pid"),
                msoMdocMeta = DCQLMetaMsoMdocExtensions(MsoMdocDocType("org.iso.18013.5.1.mDL")),
            ),
        ),
    )
}

private fun Presentation.redisRoundTrip(previous: StoredPresentation?): Presentation {
    val encoded = jsonSupport.encodeToString(toStored(previous))
    return jsonSupport.decodeFromString<StoredPresentation>(encoded).toDomain()
}
