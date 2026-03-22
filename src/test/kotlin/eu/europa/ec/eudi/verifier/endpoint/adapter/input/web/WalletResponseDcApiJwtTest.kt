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

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.EncryptedJWT
import com.nimbusds.jwt.JWTClaimsSet
import eu.europa.ec.eudi.verifier.endpoint.VerifierApplicationTest
import eu.europa.ec.eudi.verifier.endpoint.domain.RequestId
import eu.europa.ec.eudi.verifier.endpoint.port.input.InitTransactionResponse
import eu.europa.ec.eudi.verifier.endpoint.port.out.presentation.ValidateVerifiablePresentation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.util.LinkedMultiValueMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@VerifierApplicationTest([WalletResponseDcApiJwtTest.Config::class])
@TestPropertySource(
    properties = [
        "verifier.response.mode=DcApiJwt",
        "verifier.expected-origins=https://verifier.example.com",
    ],
)
@TestMethodOrder(OrderAnnotation::class)
internal class WalletResponseDcApiJwtTest {

    private val log: Logger = LoggerFactory.getLogger(WalletResponseDcApiJwtTest::class.java)

    @Autowired
    private lateinit var client: WebTestClient

    @TestConfiguration
    internal class Config {
        @Bean
        @Primary
        fun validateVerifiablePresentation(): ValidateVerifiablePresentation = ValidateVerifiablePresentation.NoOp
    }

    companion object {
        // Shared state between ordered tests
        @Volatile
        private var sharedTransactionId: String? = null

        @Volatile
        private var sharedRequestId: String? = null

        @Volatile
        private var sharedRequestUri: String? = null

        @Volatile
        private var sharedNonce: String? = null

        @Volatile
        private var sharedEcKey: ECKey? = null
    }

    @Test
    @Order(1)
    fun `initTransaction - server DcApiJwt config used when no responseMode in body`() = runTest {
        val initTransactionTO = VerifierApiClient.loadInitTransactionTO("02-dcql.json")
            .copy(responseMode = null, expectedOrigins = null)

        val response = assertIs<InitTransactionResponse.JwtSecuredAuthorizationRequestTO>(
            VerifierApiClient.initTransaction(client, initTransactionTO),
        )

        log.info("initTransaction response: $response")

        assertNotNull(response.transactionId, "transactionId should be present")
        val resolvedRequestUri = response.requestUri
        assertNotNull(resolvedRequestUri, "requestUri should be present")

        sharedTransactionId = response.transactionId
        sharedRequestId = resolvedRequestUri.removePrefix("http://localhost:0/wallet/request.jwt/")
        sharedRequestUri = resolvedRequestUri

        log.info("transactionId=$sharedTransactionId, requestId=$sharedRequestId")
    }

    @Test
    @Order(2)
    fun `getRequestObject - response_mode is dc_api dot jwt, expected_origins contains server origin, no response_uri`() = runTest {
        val uri = assertNotNull(sharedRequestUri, "requestUri from step 1 must be set")

        val payload = WalletApiClient.getRequestObjectJsonResponse(client, uri)

        log.info("request object payload: $payload")

        // Assert response_mode == "dc_api.jwt"
        val responseMode = payload["response_mode"]?.jsonPrimitive?.content
        assertEquals("dc_api.jwt", responseMode, "response_mode should be dc_api.jwt")

        // Assert expected_origins contains "https://verifier.example.com"
        val expectedOrigins = payload["expected_origins"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(expectedOrigins, "expected_origins should be present")
        assertTrue(
            "https://verifier.example.com" in expectedOrigins,
            "expected_origins should contain 'https://verifier.example.com', got: $expectedOrigins",
        )

        // Assert response_uri is absent (DC API mode doesn't use response_uri)
        val responseUri = payload["response_uri"]
        assertNull(responseUri, "response_uri should be absent for dc_api.jwt mode")

        // Assert nonce is present
        val nonce = payload["nonce"]?.jsonPrimitive?.content
        assertNotNull(nonce, "nonce should be present")

        // Extract ephemeral EC key from client_metadata.jwks
        val ecKey = payload.ecKey()
        assertNotNull(ecKey, "ephemeral EC key should be present in client_metadata.jwks")

        sharedNonce = nonce
        sharedEcKey = ecKey

        log.info("nonce=$nonce, ecKey.keyID=${ecKey.keyID}")
    }

    @Test
    @Order(3)
    fun `postWalletResponse - wallet submits JWE response and gets HTTP 200`() = runTest {
        val requestId = assertNotNull(sharedRequestId, "requestId from step 1 must be set")
        val nonce = assertNotNull(sharedNonce, "nonce from step 2 must be set")
        val ecKey = assertNotNull(sharedEcKey, "ecKey from step 2 must be set")

        // Build minimal vp_token JWT claims — vp_token must be a JSON object (map of credential id → list)
        val jwtClaims: JWTClaimsSet = buildJsonObject {
            put("state", requestId)
            put(
                "vp_token",
                buildJsonObject {
                    put(
                        "wa_driver_license",
                        buildJsonArray { add("dummy_sd_jwt_vc") },
                    )
                },
            )
        }.run { JWTClaimsSet.parse(Json.encodeToString(this)) }

        log.info("jwtClaims: ${jwtClaims.toJSONObject()}")

        // Build JWE header: ECDH-ES + A128GCM, apv=nonce
        val jweAlgorithmName = assertNotNull(ecKey.algorithm, "ecKey.algorithm must be set").name
        val algorithm = JWEAlgorithm.parse(jweAlgorithmName)
        val jweHeader = JWEHeader.Builder(algorithm, EncryptionMethod.A128GCM)
            .agreementPartyVInfo(Base64URL.encode(nonce))
            .build()

        log.info("jweHeader: ${jweHeader.toJSONObject()}")

        // Encrypt
        val encryptedJWT = EncryptedJWT(jweHeader, jwtClaims)
        encryptedJWT.encrypt(ECDHEncrypter(ecKey))
        val jwtString = encryptedJWT.serialize()

        log.info("encrypted JWT serialized length: ${jwtString.length}")

        // POST as form field response=<JWE> to /wallet/direct_post/{requestId}
        val formEncodedBody = LinkedMultiValueMap<String, Any>()
        formEncodedBody.add("response", jwtString)

        WalletApiClient.directPostJwt(client, RequestId(requestId), formEncodedBody)

        log.info("wallet response posted successfully")
    }
}
