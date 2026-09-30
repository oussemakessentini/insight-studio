package com.oussamaksantini.insightstudio.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** When an email is queued: never before the transaction that stored its token commits. */
class MailDeliveryTest {

    private final List<Runnable> queued = new ArrayList<>();
    private final MailDelivery mail = new MailDelivery(new JavaMailSenderImpl(), queued::add, "Insight Studio <no-reply@example.com>");

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void withoutATransactionTheEmailIsQueuedAtOnce() {
        mail.send("test", "someone@example.com", "Subject", "Body");
        assertThat(queued).hasSize(1);
    }

    @Test
    void insideATransactionTheEmailWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        mail.send("test", "someone@example.com", "Subject", "Body");
        assertThat(queued).isEmpty();

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
        assertThat(queued).hasSize(1);
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        mail.send("test", "someone@example.com", "Subject", "Body");
        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(queued).isEmpty();
    }

    @Test
    void aFullQueueDropsTheEmailWithoutFailingTheRequest() {
        MailDelivery full = new MailDelivery(new JavaMailSenderImpl(), task -> {
            throw new RejectedExecutionException("full");
        }, "Insight Studio <no-reply@example.com>");
        full.send("test", "someone@example.com", "Subject", "Body");
    }
}
