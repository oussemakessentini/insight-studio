package com.oussamaksantini.insightstudio.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development stand-in for email delivery: logs the reset link at INFO on the
 * {@value #LOGGER_NAME} logger. The link contains a secret token, so this is the only place that
 * logs it. Replace it with a real mail notifier (any other {@link PasswordResetNotifier} bean)
 * before other people use the API.
 */
class LoggingPasswordResetNotifier implements PasswordResetNotifier {

    static final String LOGGER_NAME = "insight.password-reset";
    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    @Override
    public void sendResetLink(String email, String displayName, String resetLink) {
        log.info("Password reset requested for {} (no mail server configured). Reset link: {}", email, resetLink);
    }
}
