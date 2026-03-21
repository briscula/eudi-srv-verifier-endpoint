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

import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import com.nimbusds.jose.jwk.JWK
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.json.jsonSupport
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils.getOrThrow
import eu.europa.ec.eudi.verifier.endpoint.domain.*
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.*
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import kotlin.time.Instant
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull

class PresentationRedisRepo(
    private val redis: ReactiveStringRedisTemplate,
    private val keyPrefix: String = "verifier",
    private val ttl: Duration = Duration.ofMinutes(30),
) {

    private val values = redis.opsForValue()
    private val lists = redis.opsForList()
    private val zSets = redis.opsForZSet()

    val loadPresentationById: LoadPresentationById by lazy {
        LoadPresentationById { transactionId ->
            readStoredPresentation(transactionId)?.toDomain()
        }
    }

    val loadPresentationByRequestId: LoadPresentationByRequestId by lazy {
        LoadPresentationByRequestId { requestId ->
            values.get(requestKey(requestId)).awaitSingleOrNull()
                ?.let(::TransactionId)
                ?.let { loadPresentationById(it) }
        }
    }

    val loadIncompletePresentationsOlderThan: LoadIncompletePresentationsOlderThan by lazy {
        LoadIncompletePresentationsOlderThan { at ->
            zSets.rangeByScore(incompleteIndexKey(), Range.closed(0.0, at.toEpochMilliseconds().toDouble()))
                .collectList()
                .awaitSingle()
                .mapNotNull { transactionId ->
                    loadPresentationById(TransactionId(transactionId))
                        ?.takeIf { it !is Presentation.TimedOut }
                }
        }
    }

    val storePresentation: StorePresentation by lazy {
        StorePresentation { presentation ->
            val previous = readStoredPresentation(presentation.id)
            val stored = presentation.toStored(previous)

            values.set(transactionKey(presentation.id), encode(stored), ttl).awaitSingle()
            zSets.add(initiatedIndexKey(), presentation.id.value, presentation.initiatedAt.toEpochMilliseconds().toDouble()).awaitSingle()

            when (presentation) {
                is Presentation.TimedOut -> {
                    previous?.requestId?.let { values.delete(requestKey(RequestId(it))).awaitSingle() }
                    zSets.remove(incompleteIndexKey(), presentation.id.value).awaitSingle()
                }

                else -> {
                    val requestId = requireNotNull(stored.requestId) {
                        "RequestId must be persisted for active presentations"
                    }
                    values.set(requestKey(RequestId(requestId)), presentation.id.value, ttl).awaitSingle()
                    zSets.add(incompleteIndexKey(), presentation.id.value, presentation.timeoutIndexScore()).awaitSingle()
                }
            }
        }
    }

    val loadPresentationEvents: LoadPresentationEvents by lazy {
        LoadPresentationEvents { transactionId ->
            lists.range(eventsKey(transactionId), 0, -1)
                .collectList()
                .awaitSingle()
                .map { decode<StoredPresentationEventRecord>(it).toRecord() }
                .toNonEmptyListOrNull()
        }
    }

    val publishPresentationEvent: PublishPresentationEvent by lazy {
        PublishPresentationEvent { event ->
            logEvent(event)
            lists.rightPush(eventsKey(event.transactionId), encode(StoredPresentationEventRecord.from(event.toRecord()))).awaitSingle()
            redis.expire(eventsKey(event.transactionId), ttl).awaitSingle()
        }
    }

    val deletePresentationsInitiatedBefore: DeletePresentationsInitiatedBefore by lazy {
        DeletePresentationsInitiatedBefore { at ->
            zSets.rangeByScore(initiatedIndexKey(), Range.closed(0.0, at.toEpochMilliseconds().toDouble()))
                .collectList()
                .awaitSingle()
                .map(::TransactionId)
                .onEach { deleteTransaction(it) }
        }
    }

    private suspend fun deleteTransaction(transactionId: TransactionId) {
        val previous = readStoredPresentation(transactionId)
        previous?.requestId?.let { values.delete(requestKey(RequestId(it))).awaitSingle() }
        values.delete(transactionKey(transactionId)).awaitSingle()
        redis.delete(eventsKey(transactionId)).awaitSingle()
        zSets.remove(initiatedIndexKey(), transactionId.value).awaitSingle()
        zSets.remove(incompleteIndexKey(), transactionId.value).awaitSingle()
    }

    private suspend fun readStoredPresentation(transactionId: TransactionId): StoredPresentation? =
        values.get(transactionKey(transactionId)).awaitSingleOrNull()?.let(::decode)

    private fun Presentation.timeoutIndexScore(): Double =
        when (this) {
            is Presentation.Requested -> initiatedAt.toEpochMilliseconds().toDouble()
            is Presentation.RequestObjectRetrieved -> requestObjectRetrievedAt.toEpochMilliseconds().toDouble()
            is Presentation.Submitted -> initiatedAt.toEpochMilliseconds().toDouble()
            is Presentation.TimedOut -> error("TimedOut presentations have no timeout index score")
        }

    private fun transactionKey(id: TransactionId): String = "$keyPrefix:presentation:tx:${id.value}"
    private fun requestKey(id: RequestId): String = "$keyPrefix:presentation:req:${id.value}"
    private fun eventsKey(id: TransactionId): String = "$keyPrefix:presentation:events:${id.value}"
    private fun initiatedIndexKey(): String = "$keyPrefix:presentation:initiated-zset"
    private fun incompleteIndexKey(): String = "$keyPrefix:presentation:incomplete-zset"

    private inline fun <reified T> decode(value: String): T = jsonSupport.decodeFromString(value)
    private inline fun <reified T> encode(value: T): String = jsonSupport.encodeToString(value)
}

@Serializable
private data class StoredPresentation(
    val kind: StoredPresentationKind,
    val transactionId: String,
    val initiatedAt: Long,
    val requestId: String? = null,
    val requestObjectRetrievedAt: Long? = null,
    val submittedAt: Long? = null,
    val timedOutAt: Long? = null,
    val query: DCQL? = null,
    val transactionData: List<String>? = null,
    val requestUriMethod: RequestUriMethod? = null,
    val nonce: String? = null,
    val responseMode: StoredResponseMode? = null,
    val getWalletResponseMethod: StoredGetWalletResponseMethod? = null,
    val issuerChain: List<String>? = null,
    val profile: StoredProfile? = null,
    val walletResponse: StoredWalletResponse? = null,
    val responseCode: String? = null,
) {
    fun toDomain(): Presentation {
        val requested = requested()
        return when (kind) {
            StoredPresentationKind.Requested -> requested
            StoredPresentationKind.RequestObjectRetrieved ->
                requested.retrieveRequestObject(clockAt(requireNotNull(requestObjectRetrievedAt))).getOrThrow()
            StoredPresentationKind.Submitted -> {
                val retrieved = requested.retrieveRequestObject(clockAt(requireNotNull(requestObjectRetrievedAt))).getOrThrow()
                retrieved.submit(
                    clock = clockAt(requireNotNull(submittedAt)),
                    walletResponse = requireNotNull(walletResponse).toDomain(),
                    responseCode = responseCode?.let(::ResponseCode),
                ).getOrThrow()
            }

            StoredPresentationKind.TimedOut -> {
                val previousState: Presentation = when {
                    submittedAt != null -> {
                        val retrieved = requested.retrieveRequestObject(clockAt(requireNotNull(requestObjectRetrievedAt))).getOrThrow()
                        retrieved.submit(
                            clock = clockAt(submittedAt),
                            walletResponse = requireNotNull(walletResponse).toDomain(),
                            responseCode = responseCode?.let(::ResponseCode),
                        ).getOrThrow()
                    }

                    requestObjectRetrievedAt != null ->
                        requested.retrieveRequestObject(clockAt(requestObjectRetrievedAt)).getOrThrow()

                    else -> requested
                }
                when (previousState) {
                    is Presentation.Requested -> previousState.timedOut(clockAt(requireNotNull(timedOutAt))).getOrThrow()
                    is Presentation.RequestObjectRetrieved -> previousState.timedOut(clockAt(requireNotNull(timedOutAt))).getOrThrow()
                    is Presentation.Submitted -> previousState.timedOut(clockAt(requireNotNull(timedOutAt))).getOrThrow()
                    is Presentation.TimedOut -> previousState
                }
            }
        }
    }

    private fun requested(): Presentation.Requested =
        Presentation.Requested(
            id = TransactionId(transactionId),
            initiatedAt = fromEpochMilliseconds(initiatedAt),
            query = requireNotNull(query),
            transactionData = transactionData?.map { TransactionData.fromBase64Url(it).getOrThrow() }?.toNonEmptyListOrNull(),
            requestId = RequestId(requireNotNull(requestId)),
            requestUriMethod = requireNotNull(requestUriMethod),
            nonce = Nonce(requireNotNull(nonce)),
            responseMode = requireNotNull(responseMode).toDomain(),
            getWalletResponseMethod = requireNotNull(getWalletResponseMethod).toDomain(),
            issuerChain = issuerChain?.map(::decodeCertificate)?.toNonEmptyListOrNull(),
            profile = requireNotNull(profile).toDomain(),
        )
}

private enum class StoredPresentationKind {
    Requested,
    RequestObjectRetrieved,
    Submitted,
    TimedOut,
}

@Serializable
private data class StoredResponseMode(
    val kind: StoredResponseModeKind,
    val expectedOrigins: List<String> = emptyList(),
    val ephemeralResponseEncryptionKey: String? = null,
) {
    fun toDomain(): ResponseMode =
        when (kind) {
            StoredResponseModeKind.DirectPost -> ResponseMode.DirectPost
            StoredResponseModeKind.DirectPostJwt -> ResponseMode.DirectPostJwt(requireNotNull(ephemeralResponseEncryptionKey).toJwk())
            StoredResponseModeKind.DcApi -> ResponseMode.DcApi(expectedOrigins)
            StoredResponseModeKind.DcApiJwt ->
                ResponseMode.DcApiJwt(expectedOrigins, requireNotNull(ephemeralResponseEncryptionKey).toJwk())
        }
}

private enum class StoredResponseModeKind {
    DirectPost,
    DirectPostJwt,
    DcApi,
    DcApiJwt,
}

@Serializable
private data class StoredGetWalletResponseMethod(
    val kind: StoredGetWalletResponseMethodKind,
    val redirectUriTemplate: String? = null,
) {
    fun toDomain(): GetWalletResponseMethod =
        when (kind) {
            StoredGetWalletResponseMethodKind.Poll -> GetWalletResponseMethod.Poll
            StoredGetWalletResponseMethodKind.Redirect -> GetWalletResponseMethod.Redirect(requireNotNull(redirectUriTemplate))
        }
}

private enum class StoredGetWalletResponseMethodKind {
    Poll,
    Redirect,
}

@Serializable
private enum class StoredProfile {
    OpenId4VP,
    HAIP,
}

private fun StoredProfile.toDomain(): Profile =
    when (this) {
        StoredProfile.OpenId4VP -> Profile.OpenId4VP
        StoredProfile.HAIP -> Profile.HAIP
    }

@Serializable
private data class StoredWalletResponse(
    val kind: StoredWalletResponseKind,
    val error: String? = null,
    val description: String? = null,
    val verifiablePresentations: Map<String, List<StoredVerifiablePresentation>>? = null,
) {
    fun toDomain(): WalletResponse =
        when (kind) {
            StoredWalletResponseKind.Error -> WalletResponse.Error(requireNotNull(error), description)
            StoredWalletResponseKind.VpToken -> WalletResponse.VpToken(
                VerifiablePresentations(
                    requireNotNull(verifiablePresentations).mapKeys { QueryId(it.key) }
                        .mapValues { (_, presentations) -> presentations.map { it.toDomain() } },
                ),
            )
        }
}

private enum class StoredWalletResponseKind {
    Error,
    VpToken,
}

@Serializable
private data class StoredVerifiablePresentation(
    val format: String,
    val stringValue: String? = null,
    val jsonValue: JsonObject? = null,
) {
    fun toDomain(): VerifiablePresentation =
        stringValue?.let { VerifiablePresentation.Str(it, Format(format)) }
            ?: VerifiablePresentation.Json(requireNotNull(jsonValue), Format(format))
}

@Serializable
private data class StoredPresentationEventRecord(
    val timestamp: Long,
    val payload: JsonObject,
) {
    fun toRecord(): PresentationEventRecord = PresentationEventRecord(fromEpochMilliseconds(timestamp), payload)

    companion object {
        fun from(record: PresentationEventRecord) =
            StoredPresentationEventRecord(record.timestamp.toEpochMilliseconds(), record.payload)
    }
}

private fun Presentation.toStored(previous: StoredPresentation?): StoredPresentation =
    when (this) {
        is Presentation.Requested -> StoredPresentation(
            kind = StoredPresentationKind.Requested,
            transactionId = id.value,
            initiatedAt = initiatedAt.toEpochMilliseconds(),
            requestId = requestId.value,
            query = query,
            transactionData = transactionData?.map { it.base64Url },
            requestUriMethod = requestUriMethod,
            nonce = nonce.value,
            responseMode = responseMode.toStored(),
            getWalletResponseMethod = getWalletResponseMethod.toStored(),
            issuerChain = issuerChain?.map(::encodeCertificate),
            profile = profile.toStored(),
        )

        is Presentation.RequestObjectRetrieved -> StoredPresentation(
            kind = StoredPresentationKind.RequestObjectRetrieved,
            transactionId = id.value,
            initiatedAt = initiatedAt.toEpochMilliseconds(),
            requestId = requestId.value,
            requestObjectRetrievedAt = requestObjectRetrievedAt.toEpochMilliseconds(),
            query = query,
            transactionData = transactionData?.map { it.base64Url },
            requestUriMethod = previous?.requestUriMethod ?: RequestUriMethod.Get,
            nonce = nonce.value,
            responseMode = responseMode.toStored(),
            getWalletResponseMethod = getWalletResponseMethod.toStored(),
            issuerChain = issuerChain?.map(::encodeCertificate),
            profile = profile.toStored(),
        )

        is Presentation.Submitted -> {
            val current = requireNotNull(previous) {
                "Cannot persist Submitted presentation without existing request metadata"
            }
            current.copy(
                kind = StoredPresentationKind.Submitted,
                requestId = requestId.value,
                requestObjectRetrievedAt = requestObjectRetrievedAt.toEpochMilliseconds(),
                submittedAt = submittedAt.toEpochMilliseconds(),
                nonce = nonce.value,
                walletResponse = walletResponse.toStored(),
                responseCode = responseCode?.value,
            )
        }

        is Presentation.TimedOut -> {
            val current = requireNotNull(previous) {
                "Cannot persist TimedOut presentation without existing request metadata"
            }
            current.copy(
                kind = StoredPresentationKind.TimedOut,
                timedOutAt = timedOutAt.toEpochMilliseconds(),
            )
        }
    }

private fun ResponseMode.toStored(): StoredResponseMode =
    when (this) {
        ResponseMode.DirectPost -> StoredResponseMode(StoredResponseModeKind.DirectPost)
        is ResponseMode.DirectPostJwt ->
            StoredResponseMode(
                kind = StoredResponseModeKind.DirectPostJwt,
                ephemeralResponseEncryptionKey = ephemeralResponseEncryptionKey.toJSONString(),
            )

        is ResponseMode.DcApi ->
            StoredResponseMode(
                kind = StoredResponseModeKind.DcApi,
                expectedOrigins = expectedOrigins,
            )

        is ResponseMode.DcApiJwt ->
            StoredResponseMode(
                kind = StoredResponseModeKind.DcApiJwt,
                expectedOrigins = expectedOrigins,
                ephemeralResponseEncryptionKey = ephemeralResponseEncryptionKey.toJSONString(),
            )
    }

private fun GetWalletResponseMethod.toStored(): StoredGetWalletResponseMethod =
    when (this) {
        GetWalletResponseMethod.Poll -> StoredGetWalletResponseMethod(StoredGetWalletResponseMethodKind.Poll)
        is GetWalletResponseMethod.Redirect ->
            StoredGetWalletResponseMethod(
                kind = StoredGetWalletResponseMethodKind.Redirect,
                redirectUriTemplate = redirectUriTemplate,
            )
    }

private fun Profile.toStored(): StoredProfile =
    when (this) {
        Profile.OpenId4VP -> StoredProfile.OpenId4VP
        Profile.HAIP -> StoredProfile.HAIP
    }

private fun WalletResponse.toStored(): StoredWalletResponse =
    when (this) {
        is WalletResponse.Error ->
            StoredWalletResponse(
                kind = StoredWalletResponseKind.Error,
                error = value,
                description = description,
            )

        is WalletResponse.VpToken ->
            StoredWalletResponse(
                kind = StoredWalletResponseKind.VpToken,
                verifiablePresentations = verifiablePresentations.value.mapKeys { it.key.value }
                    .mapValues { (_, presentations) -> presentations.map { it.toStored() } },
            )
    }

private fun VerifiablePresentation.toStored(): StoredVerifiablePresentation =
    when (this) {
        is VerifiablePresentation.Str -> StoredVerifiablePresentation(format = format.value, stringValue = value)
        is VerifiablePresentation.Json -> StoredVerifiablePresentation(format = format.value, jsonValue = value)
    }

private fun clockAt(epochMillis: Long): Clock = Clock.fixed(fromEpochMilliseconds(epochMillis), TimeZone.UTC)

private fun String.toJwk(): JWK = JWK.parse(this)

private fun encodeCertificate(certificate: X509Certificate): String =
    Base64.getEncoder().encodeToString(certificate.encoded)

private fun decodeCertificate(encoded: String): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(
        ByteArrayInputStream(Base64.getDecoder().decode(encoded)),
    ) as X509Certificate

private val redisLogger = LoggerFactory.getLogger("EVENTS")

private fun logEvent(e: PresentationEvent) {
    fun txt(s: String) = "$s - tx: ${e.transactionId.value}"
    fun warn(s: String) = redisLogger.warn(txt(s))
    fun info(s: String) = redisLogger.info(txt(s))
    when (e) {
        is PresentationEvent.VerifierFailedToGetWalletResponse -> warn("Verifier failed to retrieve wallet response. Cause ${e.cause}")
        is PresentationEvent.WalletFailedToPostResponse -> warn("Wallet failed to post response. Cause ${e.cause}")
        is PresentationEvent.FailedToRetrieveRequestObject -> warn("Wallet failed to retrieve request object. Cause ${e.cause}")
        is PresentationEvent.PresentationExpired -> info("Presentation expired")
        is PresentationEvent.RequestObjectRetrieved -> info("Wallet retrieved Request Object")
        is PresentationEvent.TransactionInitialized -> info("Verifier initialized transaction")
        is PresentationEvent.VerifierGotWalletResponse -> info("Verifier retrieved wallet response")
        is PresentationEvent.WalletResponsePosted -> info("Wallet posted response")
        is PresentationEvent.AttestationStatusCheckSuccessful -> info("Attestation status check successful")
        is PresentationEvent.AttestationStatusCheckFailed -> warn("Attestation status check failed")
    }
}
