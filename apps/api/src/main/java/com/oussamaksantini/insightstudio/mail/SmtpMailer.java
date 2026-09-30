package com.oussamaksantini.insightstudio.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends one plain-text email over SMTP, synchronously; used by the outbox worker only. */
@Component
class SmtpMailer {

    private final JavaMailSender sender;
    private final MailConfiguration.MailSettings settings;

    SmtpMailer(JavaMailSender sender, MailConfiguration.MailSettings settings) {
        this.sender = sender;
        this.settings = settings;
    }

    /** @throws MailException or {@link MessagingException} when the server refuses or cannot be reached */
    void send(String to, String subject, String text) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(settings.from());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text, false);
        sender.send(message);
    }
}
