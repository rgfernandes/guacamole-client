/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.duo;

import com.duosecurity.Client;
import com.duosecurity.exception.DuoException;
import com.duosecurity.model.Token;
import com.google.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.duo.conf.ConfigurationService;
import org.apache.guacamole.form.RedirectField;
import org.apache.guacamole.language.TranslatableGuacamoleClientException;
import org.apache.guacamole.language.TranslatableGuacamoleInsufficientCredentialsException;
import org.apache.guacamole.language.TranslatableMessage;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.CredentialsInfo;

/**
 * Service for verifying the identity of a user against Duo.
 */
public class UserVerificationService {

    /**
     * The name of the HTTP parameter that Duo will use to communicate the
     * result of the user's attempt to authenticate with their service. This
     * parameter is provided in the GET request received when Duo redirects the
     * user back to Guacamole.
     */
    public static final String DUO_CODE_PARAMETER_NAME = "duo_code";

    /**
     * The name of the HTTP parameter that we will be using to hold the opaque
     * authentication session ID. This session ID is transmitted to Duo during
     * the initial redirect and received back from Duo via this parameter in
     * the GET request received when Duo redirects the user back to Guacamole.
     * The session ID is ultimately used to reconstitute the original
     * credentials received from the user by Guacamole such that parameter
     * tokens like GUAC_USERNAME and GUAC_PASSWORD can continue to work as
     * expected.
     */
    public static final String DUO_STATE_PARAMETER_NAME = "state";

    /**
     * The value that will be returned in the token if Duo authentication
     * was successful.
     */
    private static final String DUO_TOKEN_SUCCESS_VALUE = "allow";

    /**
     * Session manager for storing/retrieving the state of a user's
     * authentication attempt while they are redirected to the Duo service.
     */
    @Inject
    private DuoAuthenticationSessionManager sessionManager;

    /**
     * Service for retrieving Duo configuration information.
     */
    @Inject
    private ConfigurationService confService;

    /**
     * Verifies the identity of the given user via the Duo multi-factor
     * authentication service. If a signed response from Duo has not already
     * been provided, a signed response from Duo is requested in the
     * form of additional expected credentials. Any provided signed response
     * is cryptographically verified. If no signed response is present, or the
     * signed response is invalid, an exception is thrown.
     *
     * @param authenticatedUser
     *     The user whose identity should be verified against Duo.
     *
     * @throws GuacamoleException
     *     If required Duo-specific configuration options are missing or
     *     malformed, or if the user's identity cannot be verified.
     */
    public void verifyAuthenticatedUser(AuthenticatedUser authenticatedUser)
            throws GuacamoleException {

        // Ignore anonymous users (unverifiable)
        String username = authenticatedUser.getIdentifier();
        if (username.equals(AuthenticatedUser.ANONYMOUS_IDENTIFIER))
            return;

        // Obtain a Duo client for redirecting the user to the Duo service and
        // verifying any received authentication code
        Client duoClient;
        try {
            duoClient = new Client.Builder(
                    confService.getClientId(),
                    confService.getClientSecret(),
                    confService.getAPIHostname(),
                    confService.getRedirectUri().toString())
                    .build();
        }
        catch (DuoException e) {
            throw new GuacamoleServerException("Client for communicating with "
                    + "the Duo authentication service could not be created.", e);
        }

        // Verify that the Duo service is healthy and available
        try {
            duoClient.healthCheck();
        }
        catch (DuoException e) {
            throw new GuacamoleServerException("Duo authentication service is "
                    + "not currently available (failed health check).", e);
        }

        // Pull the original HTTP request used to authenticate, as well as any
        // associated credentials
        Credentials credentials = authenticatedUser.getCredentials();
        HttpServletRequest request = credentials.getRequest();

        // Retrieve signed Duo authentication code and session state from the
        // request (these will be absent if this is an initial authentication
        // attempt and not a redirect back from Duo)
        String duoCode = request.getParameter(DUO_CODE_PARAMETER_NAME);
        String duoState = request.getParameter(DUO_STATE_PARAMETER_NAME);

        // Redirect to Duo to obtain an authentication code if that redirect
        // has not yet occurred
        if (duoCode == null || duoState == null) {

            // Store received credentials for later retrieval leveraging Duo's
            // opaque session state identifier (we need to maintain these
            // credentials so that things like the GUAC_USERNAME and
            // GUAC_PASSWORD tokens continue to work as expected despite the
            // redirect to/from the external Duo service)
            duoState = duoClient.generateState();
            long expirationTimestamp = System.currentTimeMillis() + (confService.getAuthenticationTimeout() * 60000L);
            sessionManager.defer(new DuoAuthenticationSession(credentials, expirationTimestamp), duoState);

            // Obtain authentication URL from Duo client
            String duoAuthUrlString;
            try {
                duoAuthUrlString = duoClient.createAuthUrl(username, duoState);
            }
            catch (DuoException e) {
                throw new GuacamoleServerException("Duo client failed to "
                        + "generate the authentication URL necessary to "
                        + "redirect the authenticating user to the Duo "
                        + "service.", e);
            }

            // Parse and validate URL obtained from Duo client
            URI duoAuthUrl;
            try {
                duoAuthUrl = new URI(duoAuthUrlString);
            }
            catch (URISyntaxException e) {
                throw new GuacamoleServerException("Authentication URL "
                        + "generated by the Duo client is not actually a "
                        + "valid URL and cannot be used to redirect the "
                        + "authenticating user to the Duo service.", e);
            }

            // Request that user be redirected to the Duo service to obtain
            // a Duo authentication code
            throw new TranslatableGuacamoleInsufficientCredentialsException(
                "Verification using Duo is required before authentication "
                + "can continue.", "LOGIN.INFO_DUO_AUTH_REQUIRED",
                new CredentialsInfo(Collections.singletonList(
                    new RedirectField(
                            DUO_CODE_PARAMETER_NAME, duoAuthUrl,
                            new TranslatableMessage("LOGIN.INFO_DUO_REDIRECT_PENDING")
                    )
                ))
            );

        }

        // Validate that the user has successfully verified their identify with
        // the Duo service
        try {
            Token token = duoClient.exchangeAuthorizationCodeFor2FAResult(duoCode, username);
            if (token == null || token.getAuth_result() == null
                    || !DUO_TOKEN_SUCCESS_VALUE.equals(token.getAuth_result().getStatus()))
                throw new TranslatableGuacamoleClientException("Provided Duo "
                        + "validation code is incorrect.",
                        "LOGIN.INFO_DUO_VALIDATION_CODE_INCORRECT");
        }
        catch (DuoException e) {
            throw new GuacamoleServerException("Duo client refused to verify "
                    + "the identity of the authenticating user due to an "
                    + "underlying error condition.", e);
        }

    }

}
