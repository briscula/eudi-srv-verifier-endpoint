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
package eu.europa.ec.eudi.verifier.endpoint.port.input

import arrow.core.NonEmptyList
import arrow.core.max
import eu.europa.ec.eudi.verifier.endpoint.domain.TransactionId
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.LoadPresentationById
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.LoadPresentationEvents
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.PresentationEventRecord
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

@Serializable
data class PresentationEventsTO(
    @SerialName("transaction_id") @Required val transactionId: String,
    @SerialName("last_updated") @Required val lastUpdated: Long,
    @SerialName("events") @Required val events: List<JsonObject>,
)

fun interface GetPresentationEvents {
    suspend operator fun invoke(transactionId: TransactionId): QueryResponse<PresentationEventsTO>
}

class GetPresentationEventsLive(

    private val loadPresentationById: LoadPresentationById,
    private val loadPresentationEvents: LoadPresentationEvents,
) : GetPresentationEvents {
    override suspend fun invoke(
        transactionId: TransactionId,
    ): QueryResponse<PresentationEventsTO> = coroutineScope {
        if (presentationExists(transactionId)) {
            val events = loadPresentationEvents(transactionId)
            checkNotNull(events) { "Didn't find any events for transaction $transactionId" }
            val lastTimestamp = events.map { it.timestamp }.max()
            val transferObject = PresentationEventsTO(transactionId, lastTimestamp, events)
            QueryResponse.Found(transferObject)
        } else {
            QueryResponse.NotFound
        }
    }

    private suspend fun presentationExists(transactionId: TransactionId): Boolean =
        loadPresentationById(transactionId) != null
}

private operator fun PresentationEventsTO.Companion.invoke(
    transactionId: TransactionId,
    lastUpdated: Instant,
    events: NonEmptyList<PresentationEventRecord>,
) =
    PresentationEventsTO(
        transactionId = transactionId.value,
        lastUpdated = lastUpdated.toEpochMilliseconds(),
        events = events.map { it.payload }.toList(),
    )
