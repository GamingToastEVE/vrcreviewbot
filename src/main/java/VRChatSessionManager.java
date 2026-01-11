import com.bastiaanjansen.otp.HMACAlgorithm;
import com.bastiaanjansen.otp.TOTPGenerator;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.vrchatapi.*;
import io.github.vrchatapi.auth.*;
import io.github.vrchatapi.api.*;
import io.github.vrchatapi.model.*;
import java.time.Duration;
import java.util.function.Supplier;

public class VRChatSessionManager {

    // Load these from Environment Variables!
    private static final Dotenv dotenv = Dotenv.load();
    private static final String BOT_USER = dotenv.get("VRC_USER");
    private static final String BOT_PASS = dotenv.get("VRC_PASS");
    private static final String TOTP_SECRET = dotenv.get("VRC_TOTP_SECRET");

    private ApiClient client;

    public ApiClient login() {
        client = new ApiClient();
        client.setUserAgent("MyDiscordBot/1.0 (contact@email.com)");
        client.setUsername(BOT_USER);
        client.setPassword(BOT_PASS);

        AuthenticationApi authApi = new AuthenticationApi(client);

        try {
            // Check if we are already logged in (cookie valid)
            authApi.getCurrentUser();
            System.out.println("Login valid (Cookie used).");
        } catch (Exception e) {
            // Cookie invalid -> Perform full login with TOTP
            System.out.println("Session expired. Performing TOTP Auto-Login...");
            performTotpLogin(authApi);
        }
        return client;
    }

    /**
     * Re-authenticates the session by performing a fresh login.
     */
    public void reAuthenticate() {
        System.out.println("Re-authenticating VRChat session...");
        if (client == null) {
            client = new ApiClient();
            client.setUserAgent("MyDiscordBot/1.0 (contact@email.com)");
            client.setUsername(BOT_USER);
            client.setPassword(BOT_PASS);
        }
        AuthenticationApi authApi = new AuthenticationApi(client);
        performTotpLogin(authApi);
    }

    /**
     * Executes a VRChat API call with automatic re-authentication on 401 errors.
     * @param apiCall The API call to execute (as a Supplier)
     * @param <T> The return type of the API call
     * @return The result of the API call
     * @throws ApiException If the API call fails after retry
     */
    public <T> T executeWithReauth(Supplier<T> apiCall) throws ApiException {
        try {
            return apiCall.get();
        } catch (Exception e) {
            // Handle cases where Supplier wraps ApiException in RuntimeException
            if (e.getCause() instanceof ApiException apiEx) {
                if (apiEx.getCode() == 401) {
                    System.out.println("Received 401 Unauthorized. Attempting re-authentication...");
                    reAuthenticate();
                    return apiCall.get();
                }
                throw apiEx;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes a VRChat API call (void return) with automatic re-authentication on 401 errors.
     * @param apiCall The API call to execute (as a Runnable)
     * @throws ApiException If the API call fails after retry
     */
    public void executeWithReauth(Runnable apiCall) throws ApiException {
        try {
            apiCall.run();
        } catch (Exception e) {
            if (e.getCause() instanceof ApiException apiEx) {
                if (apiEx.getCode() == 401) {
                    System.out.println("Received 401 Unauthorized. Attempting re-authentication...");
                    reAuthenticate();
                    apiCall.run();
                    return;
                }
                throw apiEx;
            }
            throw new RuntimeException(e);
        }
    }

    public ApiClient getClient() {
        return client;
    }

    private void performTotpLogin(AuthenticationApi authApi) {
        try {
            // 1. Generate the 6-digit code based on the Secret
            TOTPGenerator.Builder builder = new TOTPGenerator.Builder(TOTP_SECRET.getBytes());
            TOTPGenerator totp = builder
                    .withPeriod(Duration.ofSeconds(30))
                    .build();

            String code = totp.now();

            // 2. Send code to VRChat
            TwoFactorAuthCode authCode = new TwoFactorAuthCode();
            authCode.setCode(code);
            authApi.verify2FA(authCode);

            System.out.println("2FA Login successful!");
        } catch (Exception ex) {
            System.err.println("Critical Login Error: " + ex.getMessage());
        }
    }
}
