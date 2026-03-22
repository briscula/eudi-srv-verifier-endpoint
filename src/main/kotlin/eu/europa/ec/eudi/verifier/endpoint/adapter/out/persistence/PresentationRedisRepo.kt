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
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.json.jsonSupport
import eu.europa.ec.eudi.verifier.endpoint.domain.*
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

class PresentationRedisRepo(
    private val redis: ReactiveStringRedisTemplate,
    private val keyPrefix: String = "verifier",
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

            values.set(transactionKey(presentation.id), encode(stored)).awaitSingle()
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
                    values.set(requestKey(RequestId(requestId)), presentation.id.value).awaitSingle()
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
