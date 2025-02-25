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

package org.eclipse.edc.iam.identitytrust.verification;

import com.apicatalog.jsonld.loader.SchemeRouter;
import com.apicatalog.vc.integrity.DataIntegrityProofOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.eclipse.edc.iam.did.spi.document.DidConstants;
import org.eclipse.edc.iam.did.spi.document.DidDocument;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.iam.did.spi.document.VerificationMethod;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.identitytrust.model.CredentialFormat;
import org.eclipse.edc.identitytrust.model.VerifiablePresentationContainer;
import org.eclipse.edc.identitytrust.verification.SignatureSuiteRegistry;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.security.signature.jws2020.JwsSignature2020Suite;
import org.eclipse.edc.security.signature.jws2020.TestDocumentLoader;
import org.eclipse.edc.security.signature.jws2020.TestFunctions;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.verifiablecredentials.jwt.JwtCreationUtils;
import org.eclipse.edc.verifiablecredentials.jwt.JwtPresentationVerifier;
import org.eclipse.edc.verifiablecredentials.linkeddata.LdpVerifier;
import org.eclipse.edc.verifiablecredentials.verfiablecredentials.LdpCreationUtils;
import org.eclipse.edc.verifiablecredentials.verfiablecredentials.TestData;
import org.eclipse.edc.verification.jwt.SelfIssuedIdTokenVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.eclipse.edc.junit.assertions.AbstractResultAssert.assertThat;
import static org.eclipse.edc.verifiablecredentials.jwt.TestConstants.CENTRAL_ISSUER_DID;
import static org.eclipse.edc.verifiablecredentials.jwt.TestConstants.CENTRAL_ISSUER_KEY_ID;
import static org.eclipse.edc.verifiablecredentials.jwt.TestConstants.MY_OWN_DID;
import static org.eclipse.edc.verifiablecredentials.jwt.TestConstants.PRESENTER_KEY_ID;
import static org.eclipse.edc.verifiablecredentials.jwt.TestConstants.VP_HOLDER_ID;
import static org.eclipse.edc.verifiablecredentials.verfiablecredentials.TestData.MEMBERSHIP_CREDENTIAL_ISSUER;
import static org.eclipse.edc.verifiablecredentials.verfiablecredentials.TestData.NAME_CREDENTIAL_ISSUER;
import static org.eclipse.edc.verifiablecredentials.verfiablecredentials.TestData.VP_CONTENT_TEMPLATE;
import static org.eclipse.edc.verifiablecredentials.verfiablecredentials.TestData.createMembershipCredential;
import static org.eclipse.edc.verifiablecredentials.verfiablecredentials.TestData.createNameCredential;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MultiFormatPresentationVerifierTest {
    public static final String INVALID_SIGNATURE = "Invalid signature";
    private static final DidResolverRegistry DID_RESOLVER_REGISTRY = mock();
    private static final SignatureSuiteRegistry SIGNATURE_SUITE_REGISTRY = mock();
    private static final ObjectMapper MAPPER = JacksonJsonLd.createObjectMapper();
    private static final JwsSignature2020Suite JWS_SIGNATURE_SUITE = new JwsSignature2020Suite(MAPPER);
    private static ECKey vpSigningKey;
    private static ECKey vcSigningKey;
    private static TitaniumJsonLd jsonLd;
    private final TestDocumentLoader testDocLoader = new TestDocumentLoader("https://org.eclipse.edc/", "", SchemeRouter.defaultInstance());
    private MultiFormatPresentationVerifier multiFormatVerifier;

    @BeforeAll
    static void prepare() throws URISyntaxException, JOSEException {
        when(SIGNATURE_SUITE_REGISTRY.getAllSuites()).thenReturn(Collections.singleton(JWS_SIGNATURE_SUITE));
        jsonLd = new TitaniumJsonLd(mock());
        jsonLd.registerCachedDocument("https://www.w3.org/ns/odrl.jsonld", Thread.currentThread().getContextClassLoader().getResource("odrl.jsonld").toURI());
        jsonLd.registerCachedDocument("https://www.w3.org/ns/did/v1", Thread.currentThread().getContextClassLoader().getResource("jws2020.json").toURI());
        jsonLd.registerCachedDocument("https://w3id.org/security/suites/jws-2020/v1", Thread.currentThread().getContextClassLoader().getResource("jws2020.json").toURI());
        jsonLd.registerCachedDocument("https://www.w3.org/2018/credentials/v1", Thread.currentThread().getContextClassLoader().getResource("credentials.v1.json").toURI());
        jsonLd.registerCachedDocument("https://www.w3.org/2018/credentials/examples/v1", Thread.currentThread().getContextClassLoader().getResource("examples.v1.json").toURI());

        vpSigningKey = new ECKeyGenerator(Curve.P_256).keyID(PRESENTER_KEY_ID).generate();
        vcSigningKey = new ECKeyGenerator(Curve.P_256).keyID(CENTRAL_ISSUER_KEY_ID).generate();
        // the DID document of the VP presenter (i.e. a participant agent)
        var vpPresenterDid = DidDocument.Builder.newInstance()
                .verificationMethod(List.of(VerificationMethod.Builder.create()
                        .id(PRESENTER_KEY_ID)
                        .type(DidConstants.ECDSA_SECP_256_K_1_VERIFICATION_KEY_2019)
                        .publicKeyJwk(vpSigningKey.toPublicJWK().toJSONObject())
                        .build()))
                .service(Collections.singletonList(new Service("#my-service1", "MyService", "http://doesnotexi.st")))
                .build();

        // the DID document of the central issuer, e.g. a government body, etc.
        var vcIssuerDid = DidDocument.Builder.newInstance()
                .verificationMethod(List.of(VerificationMethod.Builder.create()
                        .id(CENTRAL_ISSUER_KEY_ID)
                        .type(DidConstants.ECDSA_SECP_256_K_1_VERIFICATION_KEY_2019)
                        .publicKeyJwk(vcSigningKey.toPublicJWK().toJSONObject())
                        .build()))
                .build();

        when(DID_RESOLVER_REGISTRY.resolve(eq(VP_HOLDER_ID))).thenReturn(Result.success(vpPresenterDid));
        when(DID_RESOLVER_REGISTRY.resolve(eq(CENTRAL_ISSUER_DID))).thenReturn(Result.success(vcIssuerDid));
    }

    @BeforeEach
    void setup() {
        var ldpVerifier = LdpVerifier.Builder.newInstance()
                .signatureSuites(SIGNATURE_SUITE_REGISTRY)
                .jsonLd(jsonLd)
                .objectMapper(MAPPER)
                .build();
        multiFormatVerifier = new MultiFormatPresentationVerifier(MY_OWN_DID, new JwtPresentationVerifier(new SelfIssuedIdTokenVerifier(DID_RESOLVER_REGISTRY), MAPPER), ldpVerifier);
    }

    private DataIntegrityProofOptions generateEmbeddedProofOptions(ECKey vcKey, String proofPurpose) {
        return JWS_SIGNATURE_SUITE
                .createOptions()
                .created(Instant.now())
                .verificationMethod(TestFunctions.createKeyPair(vcKey, proofPurpose)) // embedded proof
                .purpose(URI.create("https://w3id.org/security#assertionMethod"));
    }

    @Nested
    class JwtVp {

        @DisplayName("contains only JWT-VC (single)")
        @Test
        void verify_hasJwtVc_success() {
            // create first VC-JWT (signed by the central issuer)
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var vpContent = VP_CONTENT_TEMPLATE.formatted("\"" + vcJwt1 + "\"");
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", vpContent));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null))).isSucceeded();
        }

        @DisplayName("contains only JWT-VCs (multiple)")
        @Test
        void verify_hasJwtVcs_success() {
            // create first VC-JWT (signed by the central issuer)
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            // create first VC-JWT (signed by the central issuer)
            var vcJwt2 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "isoCred", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_CERTIFICATE_EXAMPLE));

            // create VP-JWT (signed by the presenter) that contains the VP as a claim
            var vpContent = "\"%s\", \"%s\"".formatted(vcJwt1, vcJwt2);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null))).isSucceeded();
        }

        @DisplayName("contains only LDP-VC (single)")
        @Test
        void verify_hasLdpVc_success() {
            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = VP_CONTENT_TEMPLATE.formatted(signedNameCredential);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", vpContent));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null))).isSucceeded();
        }

        @DisplayName("contains only LDP-VCs (multiple)")
        @Test
        void verify_hasLdpVcs_success() {

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);


            var vpContent = "%s, %s".formatted(signedMemberCredential, signedNameCredential);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null))).isSucceeded();
        }

        @DisplayName("containing both LDP-VC and JWT-VC")
        @Test
        void verify_mixedVcs_success() {
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = "%s, %s, \"%s\"".formatted(signedMemberCredential, signedNameCredential, vcJwt1);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null))).isSucceeded();
        }

        @DisplayName("contains only one forged JWT-VC (single)")
        @Test
        void verify_hasJwtVc_forged_fails() throws JOSEException {
            // during DID resolution, the "vcSigningKey" would be resolved, which is different from the "spoofedKey"
            var spoofedKey = new ECKeyGenerator(Curve.P_256).keyID(CENTRAL_ISSUER_KEY_ID).generate();
            var vcJwt1 = JwtCreationUtils.createJwt(spoofedKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var vpContent = VP_CONTENT_TEMPLATE.formatted("\"" + vcJwt1 + "\"");
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", vpContent));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null)))
                    .isFailed().detail().contains(INVALID_SIGNATURE);
        }

        @DisplayName("contains only JWT-VCs, one is forged ")
        @Test
        void verify_hasJwtVcs_forged_success() throws JOSEException {
            // during DID resolution, the "vcSigningKey" would be resolved, which is different from the "spoofedKey"
            var spoofedKey = new ECKeyGenerator(Curve.P_256).keyID(CENTRAL_ISSUER_KEY_ID).generate();
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            // create first VC-JWT (signed by the central issuer)
            var vcJwt2 = JwtCreationUtils.createJwt(spoofedKey, CENTRAL_ISSUER_DID, "isoCred", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_CERTIFICATE_EXAMPLE));

            // create VP-JWT (signed by the presenter) that contains the VP as a claim
            var vpContent = "\"%s\", \"%s\"".formatted(vcJwt1, vcJwt2);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null)))
                    .isFailed().detail().contains(INVALID_SIGNATURE);
        }

        @DisplayName("contains only one forged LDP-VC")
        @Test
        void verify_hasLdpVc_forged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_384).keyID("violating-key").generate();
            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(spoofedKey, NAME_CREDENTIAL_ISSUER), testDocLoader);


            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(signedNameCredential)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("contains only LDP-VCs, one is forged")
        @Test
        void verify_hasLdpVcs_forged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_384).keyID("violating-key").generate();
            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(spoofedKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);


            var vpContent = "%s, %s".formatted(signedMemberCredential, signedNameCredential);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("containing both LDP-VC and JWT-VC, the LDP-VC is forged")
        @Test
        void verify_mixedVcs_ldpForged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_384).keyID("violating-key").generate();
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(spoofedKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = "%s, %s, \"%s\"".formatted(signedMemberCredential, signedNameCredential, vcJwt1);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("containing both LDP-VC and JWT-VC, the JWT-VC is forged")
        @Test
        void verify_mixedVcs_jwtForged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_256).keyID(CENTRAL_ISSUER_KEY_ID).generate();

            var vcJwt1 = JwtCreationUtils.createJwt(spoofedKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = "%s, %s, \"%s\"".formatted(signedMemberCredential, signedNameCredential, vcJwt1);
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", VP_CONTENT_TEMPLATE.formatted(vpContent)));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null)))
                    .isFailed().detail().contains("Invalid signature");
        }

        @DisplayName("contains no VCs")
        @Test
        void verify_noCredentials() {
            // create first VC-JWT (signed by the central issuer)

            var vpContent = VP_CONTENT_TEMPLATE.formatted("");
            var vpJwt = JwtCreationUtils.createJwt(vpSigningKey, VP_HOLDER_ID, "testSub", MY_OWN_DID, Map.of("vp", vpContent));

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpJwt, CredentialFormat.JWT, null))).isSucceeded();
        }

    }

    /**
     * As per their <a href="https://www.w3.org/2018/credentials/v1">schema</a>, ldp_vp's can only contain ldp_vc's. The VerifiableCredentials Data Model
     * specification does not make that distinction though. The Multiformat verifier could handle the case, even if it's (currently) not possible.
     */
    @Nested
    class LdpVp {
        @DisplayName("contains only JWT-VC (single), which is stripped out by the expansion")
        @Test
        void verify_hasJwtVc_succeeds() {
            // create first VC-JWT (signed by the central issuer)
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var vpContent = VP_CONTENT_TEMPLATE.formatted("\"" + vcJwt1 + "\"");
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);
            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("contains only LDP-VC (single)")
        @Test
        void verify_hasLdpVc_success() {
            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = VP_CONTENT_TEMPLATE.formatted(signedNameCredential);

            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);
            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null))).isSucceeded();
        }

        @DisplayName("contains only LDP-VCs (multiple)")
        @Test
        void verify_hasLdpVcs_success() {

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);


            var vpContent = VP_CONTENT_TEMPLATE.formatted("%s, %s".formatted(signedMemberCredential, signedNameCredential));
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null))).isSucceeded();
        }

        @DisplayName("containing both LDP-VC and JWT-VC, JWT gets stripped out during expansion")
        @Test
        void verify_mixedVcs_success() {
            var spy = Mockito.spy(multiFormatVerifier.getContext().getVerifiers().stream().filter(cv -> cv instanceof JwtPresentationVerifier).findFirst().get());
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = VP_CONTENT_TEMPLATE.formatted("%s, %s, \"%s\"".formatted(signedMemberCredential, signedNameCredential, vcJwt1));
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);
            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null)))
                    .isFailed().detail().contains("InvalidSignature");
            verifyNoInteractions(spy);
        }

        @DisplayName("contains only one forged LDP-VC")
        @Test
        void verify_hasLdpVc_forged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_384).keyID("violating-key").generate();
            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(spoofedKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = VP_CONTENT_TEMPLATE.formatted("%s, %s".formatted(signedNameCredential, signedNameCredential));
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("contains only LDP-VCs, one is forged")
        @Test
        void verify_hasLdpVcs_forged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_384).keyID("violating-key").generate();
            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(spoofedKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);


            var vpContent = VP_CONTENT_TEMPLATE.formatted("%s, %s".formatted(signedMemberCredential, signedNameCredential));
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("containing both LDP-VC and JWT-VC, the LDP-VC is forged")
        @Test
        void verify_mixedVcs_ldpForged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_384).keyID("violating-key").generate();
            var vcJwt1 = JwtCreationUtils.createJwt(vcSigningKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(spoofedKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = "%s, %s, \"%s\"".formatted(signedMemberCredential, signedNameCredential, vcJwt1);
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("containing both LDP-VC and JWT-VC, the JWT-VC is forged")
        @Test
        void verify_mixedVcs_jwtForged_fails() throws JOSEException {
            var spoofedKey = new ECKeyGenerator(Curve.P_256).keyID(CENTRAL_ISSUER_KEY_ID).generate();

            var vcJwt1 = JwtCreationUtils.createJwt(spoofedKey, CENTRAL_ISSUER_DID, "degreeSub", VP_HOLDER_ID, Map.of("vc", TestData.VC_CONTENT_DEGREE_EXAMPLE));

            var nameCredential = createNameCredential();
            var signedNameCredential = LdpCreationUtils.signDocument(nameCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, NAME_CREDENTIAL_ISSUER), testDocLoader);

            var memberCredential = createMembershipCredential();
            var signedMemberCredential = LdpCreationUtils.signDocument(memberCredential, vcSigningKey, generateEmbeddedProofOptions(vcSigningKey, MEMBERSHIP_CREDENTIAL_ISSUER), testDocLoader);

            var vpContent = VP_CONTENT_TEMPLATE.formatted("%s, %s, \"%s\"".formatted(signedMemberCredential, signedNameCredential, vcJwt1));
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null)))
                    .isFailed().detail().contains("InvalidSignature");
        }

        @DisplayName("contains no VCs")
        @Test
        void verify_noCredentials() {
            // create first VC-JWT (signed by the central issuer)

            var vpContent = VP_CONTENT_TEMPLATE.formatted("");
            var vpLdp = LdpCreationUtils.signDocument(vpContent, vpSigningKey, generateEmbeddedProofOptions(vpSigningKey, VP_HOLDER_ID), testDocLoader);

            assertThat(multiFormatVerifier.verifyPresentation(new VerifiablePresentationContainer(vpLdp, CredentialFormat.JSON_LD, null))).isSucceeded();
        }

    }

}