/*
 *  Copyright (c) 2023 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

package org.eclipse.edc.iam.identitytrust.sts.embedded;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.edc.jwt.TokenGenerationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.eclipse.edc.identitytrust.SelfIssuedTokenConstants.ACCESS_TOKEN;
import static org.eclipse.edc.identitytrust.SelfIssuedTokenConstants.BEARER_ACCESS_ALIAS;
import static org.eclipse.edc.junit.assertions.AbstractResultAssert.assertThat;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.AUDIENCE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.EXPIRATION_TIME;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUED_AT;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.ISSUER;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.JWT_ID;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SCOPE;
import static org.eclipse.edc.jwt.spi.JwtRegisteredClaimNames.SUBJECT;


public class EmbeddedSecureTokenServiceIntegrationTest {

    private KeyPair keyPair;
    private EmbeddedSecureTokenService secureTokenService;

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        var gen = KeyPairGenerator.getInstance("RSA");
        return gen.generateKeyPair();
    }

    @BeforeEach
    void setup() throws NoSuchAlgorithmException {
        keyPair = generateKeyPair();
        var tokenGenerationService = new TokenGenerationServiceImpl(keyPair.getPrivate());
        secureTokenService = new EmbeddedSecureTokenService(tokenGenerationService, Clock.systemUTC(), 10 * 60);
    }

    @Test
    void createToken_withoutBearerAccessScope() {
        var issuer = "testIssuer";

        var claims = Map.of(ISSUER, issuer);
        var tokenResult = secureTokenService.createToken(claims, null);

        assertThat(tokenResult).isSucceeded()
                .satisfies(tokenRepresentation -> {
                    var jwt = SignedJWT.parse(tokenRepresentation.getToken());
                    assertThat(jwt.verify(createVerifier(jwt.getHeader(), keyPair.getPublic()))).isTrue();
                    assertThat(jwt.getJWTClaimsSet().getClaims())
                            .containsEntry(ISSUER, issuer)
                            .containsKeys(JWT_ID, EXPIRATION_TIME, ISSUED_AT)
                            .doesNotContainKey(ACCESS_TOKEN);
                });

    }

    @Test
    void createToken_withBearerAccessScope() {
        var scopes = "email:read";
        var issuer = "testIssuer";
        var audience = "audience";
        var claims = Map.of(ISSUER, issuer, AUDIENCE, audience);
        var tokenResult = secureTokenService.createToken(claims, scopes);

        assertThat(tokenResult).isSucceeded()
                .satisfies(tokenRepresentation -> {
                    var jwt = SignedJWT.parse(tokenRepresentation.getToken());
                    assertThat(jwt.verify(createVerifier(jwt.getHeader(), keyPair.getPublic()))).isTrue();

                    assertThat(jwt.getJWTClaimsSet().getClaims())
                            .containsEntry(ISSUER, issuer)
                            .containsKeys(JWT_ID, EXPIRATION_TIME, ISSUED_AT)
                            .extractingByKey(ACCESS_TOKEN, as(STRING))
                            .satisfies(accessToken -> {
                                var accessTokenJwt = SignedJWT.parse(accessToken);
                                assertThat(accessTokenJwt.verify(createVerifier(accessTokenJwt.getHeader(), keyPair.getPublic()))).isTrue();
                                assertThat(accessTokenJwt.getJWTClaimsSet().getClaims())
                                        .containsEntry(ISSUER, issuer)
                                        .containsEntry(SUBJECT, audience)
                                        .containsEntry(AUDIENCE, List.of(issuer))
                                        .containsEntry(SCOPE, scopes)
                                        .containsKeys(JWT_ID, EXPIRATION_TIME, ISSUED_AT);
                            });
                });
    }

    @Test
    void createToken_withBearerAccessAlias() {
        var scopes = "email:read";
        var issuer = "testIssuer";
        var audience = "audience";
        var bearerAccessAlias = "alias";
        var claims = Map.of(ISSUER, issuer, AUDIENCE, audience, BEARER_ACCESS_ALIAS, bearerAccessAlias);
        var tokenResult = secureTokenService.createToken(claims, scopes);

        assertThat(tokenResult).isSucceeded()
                .satisfies(tokenRepresentation -> {
                    var jwt = SignedJWT.parse(tokenRepresentation.getToken());
                    assertThat(jwt.verify(createVerifier(jwt.getHeader(), keyPair.getPublic()))).isTrue();

                    assertThat(jwt.getJWTClaimsSet().getClaims())
                            .containsEntry(ISSUER, issuer)
                            .containsKeys(JWT_ID, EXPIRATION_TIME, ISSUED_AT)
                            .extractingByKey(ACCESS_TOKEN, as(STRING))
                            .satisfies(accessToken -> {
                                var accessTokenJwt = SignedJWT.parse(accessToken);
                                assertThat(accessTokenJwt.verify(createVerifier(accessTokenJwt.getHeader(), keyPair.getPublic()))).isTrue();
                                assertThat(accessTokenJwt.getJWTClaimsSet().getClaims())
                                        .containsEntry(ISSUER, issuer)
                                        .containsEntry(SUBJECT, bearerAccessAlias)
                                        .containsEntry(AUDIENCE, List.of(issuer))
                                        .containsEntry(SCOPE, scopes)
                                        .containsKeys(JWT_ID, EXPIRATION_TIME, ISSUED_AT);
                            });
                });
    }
    
    @ParameterizedTest
    @ArgumentsSource(ClaimsArguments.class)
    void createToken_shouldFail_withMissingClaims(Map<String, String> claims) {
        var tokenResult = secureTokenService.createToken(claims, "email:read");
        assertThat(tokenResult).isFailed()
                .satisfies(f -> assertThat(f.getFailureDetail()).matches("Missing [a-z]* in the input claims"));
    }

    private JWSVerifier createVerifier(JWSHeader header, Key publicKey) throws JOSEException {
        return new DefaultJWSVerifierFactory().createJWSVerifier(header, publicKey);
    }

    private static class ClaimsArguments implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return Stream.of(Map.of(ISSUER, "iss"), Map.of(AUDIENCE, "aud")).map(Arguments::of);
        }
    }
}
