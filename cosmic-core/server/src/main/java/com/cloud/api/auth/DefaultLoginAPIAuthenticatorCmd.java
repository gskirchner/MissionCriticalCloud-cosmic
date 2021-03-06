package com.cloud.api.auth;

import com.cloud.api.APICommand;
import com.cloud.api.ApiConstants;
import com.cloud.api.ApiErrorCode;
import com.cloud.api.ApiServerService;
import com.cloud.api.BaseCmd;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.api.response.LoginCmdResponse;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.user.Account;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "login", description = "Logs a user into the CloudStack. A successful login attempt will generate a JSESSIONID cookie value that can be passed in subsequent " +
        "Query command calls until the \"logout\" command has been issued or the session has expired.", requestHasSensitiveInfo = true, responseObject = LoginCmdResponse.class,
        entityType = {})
public class DefaultLoginAPIAuthenticatorCmd extends BaseCmd implements APIAuthenticator {

    public static final Logger s_logger = LoggerFactory.getLogger(DefaultLoginAPIAuthenticatorCmd.class.getName());
    private static final String s_name = "loginresponse";
    @Inject
    ApiServerService _apiServer;
    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.USERNAME, type = CommandType.STRING, description = "Username", required = true)
    private String username;
    @Parameter(name = ApiConstants.PASSWORD, type = CommandType.STRING, description = "Hashed password (Default is MD5). If you wish to use any other hashing algorithm, you " +
            "would need to write a custom authentication adapter See Docs section.", required = true)
    private String password;
    @Parameter(name = ApiConstants.DOMAIN, type = CommandType.STRING, description = "Path of the domain that the user belongs to. Example: domain=/com/cloud/internal. If no " +
            "domain is passed in, the ROOT (/) domain is assumed.")
    private String domain;
    @Parameter(name = ApiConstants.DOMAIN__ID, type = CommandType.LONG, description = "The id of the domain that the user belongs to. If both domain and domainId are passed in, " +
            "\"domainId\" parameter takes precendence")
    private Long domainId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDomain() {
        return domain;
    }

    public Long getDomainId() {
        return domainId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException {
        // We should never reach here
        throw new ServerApiException(ApiErrorCode.METHOD_NOT_ALLOWED, "This is an authentication api, cannot be used directly");
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_TYPE_NORMAL;
    }

    @Override
    public String authenticate(final String command, final Map<String, Object[]> params, final HttpSession session, final InetAddress remoteAddress, final String responseType,
                               final StringBuilder auditTrailSb,
                               final HttpServletRequest req, final HttpServletResponse resp) throws ServerApiException {
        // Disallow non POST requests
        if (HTTPMethod.valueOf(req.getMethod()) != HTTPMethod.POST) {
            throw new ServerApiException(ApiErrorCode.METHOD_NOT_ALLOWED, "Please use HTTP POST to authenticate using this API");
        }
        // FIXME: ported from ApiServlet, refactor and cleanup
        final String[] username = (String[]) params.get(ApiConstants.USERNAME);
        final String[] password = (String[]) params.get(ApiConstants.PASSWORD);
        String[] domainIdArr = (String[]) params.get(ApiConstants.DOMAIN_ID);

        if (domainIdArr == null) {
            domainIdArr = (String[]) params.get(ApiConstants.DOMAIN__ID);
        }
        final String[] domainName = (String[]) params.get(ApiConstants.DOMAIN);
        Long domainId = null;
        if ((domainIdArr != null) && (domainIdArr.length > 0)) {
            try {
                //check if UUID is passed in for domain
                domainId = _apiServer.fetchDomainId(domainIdArr[0]);
                if (domainId == null) {
                    domainId = Long.parseLong(domainIdArr[0]);
                }
                auditTrailSb.append(" domainid=" + domainId);// building the params for POST call
            } catch (final NumberFormatException e) {
                s_logger.warn("Invalid domain id entered by user");
                auditTrailSb.append(" " + HttpServletResponse.SC_UNAUTHORIZED + " " + "Invalid domain id entered, please enter a valid one");
                throw new ServerApiException(ApiErrorCode.UNAUTHORIZED,
                        _apiServer.getSerializedApiError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid domain id entered, please enter a valid one", params,
                                responseType));
            }
        }

        String domain = null;
        if (domainName != null) {
            domain = domainName[0];
            auditTrailSb.append(" domain=" + domain);
            if (domain != null) {
                // ensure domain starts with '/' and ends with '/'
                if (!domain.endsWith("/")) {
                    domain += '/';
                }
                if (!domain.startsWith("/")) {
                    domain = "/" + domain;
                }
            }
        }

        String serializedResponse = null;
        if (username != null) {
            final String pwd = ((password == null) ? null : password[0]);
            try {
                return ApiResponseSerializer.toSerializedString(_apiServer.loginUser(session, username[0], pwd, domainId, domain, remoteAddress, params),
                        responseType);
            } catch (final CloudAuthenticationException ex) {
                // TODO: fall through to API key, or just fail here w/ auth error? (HTTP 401)
                try {
                    session.invalidate();
                } catch (final IllegalStateException ise) {
                }
                auditTrailSb.append(" " + ApiErrorCode.ACCOUNT_ERROR + " " + ex.getMessage() != null ? ex.getMessage()
                        : "failed to authenticate user, check if username/password are correct");
                serializedResponse =
                        _apiServer.getSerializedApiError(ApiErrorCode.ACCOUNT_ERROR.getHttpCode(), ex.getMessage() != null ? ex.getMessage()
                                : "failed to authenticate user, check if username/password are correct", params, responseType);
            }
        }
        // We should not reach here and if we do we throw an exception
        throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, serializedResponse);
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.LOGIN_API;
    }

    @Override
    public void setAuthenticators(final List<PluggableAPIAuthenticator> authenticators) {
    }
}
