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

import arrow.core.toNonEmptyListOrNull
import com.nimbusds.jose.jwk.JWK
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils.getOrThrow
import eu.europa.ec.eudi.verifier.endpoint.domain.*
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.PresentationEvent
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.PresentationEventRecord
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.time.Instant.Companion.fromEpochMilliseconds

@Serializable
internal data class StoredPresentation(
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
    internal fun toDomain(): Presentation {
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

internal enum class StoredPresentationKind {
    Requested,
    RequestObjectRetrieved,
    Submitted,
    TimedOut,
}

@Serializable
internal data class StoredResponseMode(
    val kind: StoredResponseModeKind,
    val expectedOrigins: List<String> = emptyList(),
    val ephemeralResponseEncryptionKey: String? = null,
) {
    internal fun toDomain(): ResponseMode =
        when (kind) {
            StoredResponseModeKind.DirectPost -> ResponseMode.DirectPost
            StoredResponseModeKind.DirectPostJwt -> ResponseMode.DirectPostJwt(requireNotNull(ephemeralResponseEncryptionKey).toJwk())
            StoredResponseModeKind.DcApi -> ResponseMode.DcApi(expectedOrigins)
            StoredResponseModeKind.DcApiJwt ->
                ResponseMode.DcApiJwt(expectedOrigins, requireNotNull(ephemeralResponseEncryptionKey).toJwk())
        }
}

internal enum class StoredResponseModeKind {
    DirectPost,
    DirectPostJwt,
    DcApi,
    DcApiJwt,
}

@Serializable
internal data class StoredGetWalletResponseMethod(
    val kind: StoredGetWalletResponseMethodKind,
    val redirectUriTemplate: String? = null,
) {
    internal fun toDomain(): GetWalletResponseMethod =
        when (kind) {
            StoredGetWalletResponseMethodKind.Poll -> GetWalletResponseMethod.Poll
            StoredGetWalletResponseMethodKind.Redirect -> GetWalletResponseMethod.Redirect(requireNotNull(redirectUriTemplate))
        }
}

internal enum class StoredGetWalletResponseMethodKind {
    Poll,
    Redirect,
}

@Serializable
internal enum class StoredProfile {
    OpenId4VP,
    HAIP,
}

internal fun StoredProfile.toDomain(): Profile =
    when (this) {
        StoredProfile.OpenId4VP -> Profile.OpenId4VP
        StoredProfile.HAIP -> Profile.HAIP
    }

@Serializable
internal data class StoredWalletResponse(
    val kind: StoredWalletResponseKind,
    val error: String? = null,
    val description: String? = null,
    val verifiablePresentations: Map<String, List<StoredVerifiablePresentation>>? = null,
) {
    internal fun toDomain(): WalletResponse =
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

internal enum class StoredWalletResponseKind {
    Error,
    VpToken,
}

@Serializable
internal data class StoredVerifiablePresentation(
    val format: String,
    val stringValue: String? = null,
    val jsonValue: JsonObject? = null,
) {
    internal fun toDomain(): VerifiablePresentation =
        stringValue?.let { VerifiablePresentation.Str(it, Format(format)) }
            ?: VerifiablePresentation.Json(requireNotNull(jsonValue), Format(format))
}

@Serializable
internal data class StoredPresentationEventRecord(
    val timestamp: Long,
    val payload: JsonObject,
) {
    internal fun toRecord(): PresentationEventRecord = PresentationEventRecord(fromEpochMilliseconds(timestamp), payload)

    companion object {
        fun from(record: PresentationEventRecord) =
            StoredPresentationEventRecord(record.timestamp.toEpochMilliseconds(), record.payload)
    }
}

internal fun Presentation.toStored(previous: StoredPresentation?): StoredPresentation =
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

internal fun ResponseMode.toStored(): StoredResponseMode =
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

internal fun GetWalletResponseMethod.toStored(): StoredGetWalletResponseMethod =
    when (this) {
        GetWalletResponseMethod.Poll -> StoredGetWalletResponseMethod(StoredGetWalletResponseMethodKind.Poll)
        is GetWalletResponseMethod.Redirect ->
            StoredGetWalletResponseMethod(
                kind = StoredGetWalletResponseMethodKind.Redirect,
                redirectUriTemplate = redirectUriTemplate,
            )
    }

internal fun Profile.toStored(): StoredProfile =
    when (this) {
        Profile.OpenId4VP -> StoredProfile.OpenId4VP
        Profile.HAIP -> StoredProfile.HAIP
    }

internal fun WalletResponse.toStored(): StoredWalletResponse =
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

internal fun VerifiablePresentation.toStored(): StoredVerifiablePresentation =
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
