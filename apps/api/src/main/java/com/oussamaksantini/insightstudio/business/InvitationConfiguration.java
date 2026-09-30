package com.oussamaksantini.insightstudio.business;

import com.oussamaksantini.insightstudio.mail.MailDelivery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InvitationConfiguration {

    /** Emails invitation links over SMTP (Mailpit in development). */
    @Bean
    InvitationNotifier invitationNotifier(MailDelivery mail) {
        return new SmtpInvitationNotifier(mail);
    }
}
