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
package eu.europa.ec.eudi.verifier.endpoint.adapter.input.web

import eu.europa.ec.eudi.verifier.endpoint.TestContext
import eu.europa.ec.eudi.verifier.endpoint.VerifierApplicationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@VerifierApplicationTest
internal class SecurityApiKeyTest {

    @Autowired
    private lateinit var client: WebTestClient

    @Test
    fun `ui endpoints require api key`() {
        client.post()
            .uri(VerifierApi.INIT_TRANSACTION_PATH_V2)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue("""{"nonce":"nonce"}""")
            .exchange()
            .expectStatus().isUnauthorized()
    }

    @Test
    fun `ui endpoints reject invalid api key`() {
        client.post()
            .uri(VerifierApi.INIT_TRANSACTION_PATH_V2)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("X-API-Key", "wrong-key")
            .bodyValue("""{"nonce":"nonce"}""")
            .exchange()
            .expectStatus().isUnauthorized()
    }

    @Test
    fun `wallet endpoints remain public`() {
        client.get()
            .uri("/wallet/public-keys.json")
            .exchange()
            .expectStatus().isOk()
    }

    @Test
    fun `ui endpoints accept valid api key`() {
        client.post()
            .uri(VerifierApi.INIT_TRANSACTION_PATH_V2)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("X-API-Key", TestContext.verifierApiKey)
            .bodyValue("""{}""")
            .exchange()
            .expectStatus().isBadRequest()
    }
}
