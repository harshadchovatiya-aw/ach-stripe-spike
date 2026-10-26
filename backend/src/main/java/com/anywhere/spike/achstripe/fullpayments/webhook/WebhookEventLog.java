package com.anywhere.spike.achstripe.fullpayments.webhook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/** Spike-only: keeps the last webhook events received so the UI can show them. */
@Component
public class WebhookEventLog {

    private static final int MAX = 100;

    public record Entry(Instant receivedAt, String eventId, String type, String objectId, String outcome) {}

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

    public void add(Entry entry) {
        entries.addFirst(entry);
        while (entries.size() > MAX) {
            entries.pollLast();
        }
    }

    public List<Entry> list() {
        return new ArrayList<>(entries);
    }
}
