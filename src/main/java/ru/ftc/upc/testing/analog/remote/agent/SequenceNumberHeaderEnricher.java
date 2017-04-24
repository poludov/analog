package ru.ftc.upc.testing.analog.remote.agent;

import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.messaging.Message;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple sequence number counter.
 * Spring Integration {@linkplain org.springframework.integration.file.dsl.TailAdapterSpec file tailing adapter} is not
 * capable of providing {@link IntegrationMessageHeaderAccessor#SEQUENCE_NUMBER SEQUENCE_NUMBER} header so that this enricher fills
 * the gap. It uses {@code long} type instead of {@code int}.
 * @author Toparvion
 * @since v0.7
 */
class SequenceNumberHeaderEnricher {
  private final AtomicLong counter = new AtomicLong(0L);

  @SuppressWarnings("unused")     // sequence number does not depend on message being counted
  long assignSequenceNumber(Message<String> lineMessage) {
    return counter.incrementAndGet();
  }
}
