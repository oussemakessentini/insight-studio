package com.oussamaksantini.insightstudio.billing;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/billing/webhooks/{provider}}: public (no session, no CSRF: the signature is the
 * authentication), body read raw so the signature covers exactly the bytes sent. {@code 200} once the
 * event is recorded (or was already), {@code 400} for an invalid one, {@code 404} for a provider that
 * is not the configured one.
 */
@RestController
class BillingWebhookController {

    private final BillingWebhooks webhooks;

    BillingWebhookController(BillingWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/billing/webhooks/{provider}")
    Map<String, Object> receive(@PathVariable String provider, @RequestBody(required = false) byte[] body,
            @RequestHeader(value = StripeSignature.HEADER, required = false) String signature) {
        webhooks.receive(provider, body, signature);
        return Map.of("received", true);
    }
}
