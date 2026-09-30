package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.mail.MailOutbox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InvitationConfiguration {

    /** Emails invitation links through the mail outbox (Mailpit in development). */
    @Bean
    InvitationNotifier invitationNotifier(MailOutbox outbox) {
        return new EmailInvitationNotifier(outbox);
    }
}
