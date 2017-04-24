package ru.ftc.upc.testing.analog.remote.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;
import ru.ftc.upc.testing.analog.model.RecordLevel;
import ru.ftc.upc.testing.analog.util.timestamp.TimestampExtractor;

import java.io.File;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Stream;

import static org.springframework.integration.IntegrationMessageHeaderAccessor.CORRELATION_ID;
import static org.springframework.integration.dsl.channel.MessageChannels.queue;
import static org.springframework.integration.file.dsl.Files.tailAdapter;
import static ru.ftc.upc.testing.analog.model.RecordLevel.UNKNOWN;
import static ru.ftc.upc.testing.analog.remote.RemotingConstants.*;

/**
 *
 * @author Toparvion
 * @since v0.7
 */
@Component
public class TailingFlowProvider {

  private final TimestampExtractor timestampExtractor;

  @Autowired
  public TailingFlowProvider(TimestampExtractor timestampExtractor) {
    this.timestampExtractor = timestampExtractor;
  }

  /**
   * The core method for building AnaLog dynamic behavior. Creates and returns an integration flow for watching given
   * log. No duplicate flow checking is done inside.
   * @param logPath full path to log file to tail
   * @return a new tailing flow
   */
  IntegrationFlow provideTailingFlow(String logPath) {
    // each tailing flow must have its own instance of correlationProvider as it is stateful and not thread-safe
    CorrelationIdHeaderEnricher correlationProvider = new CorrelationIdHeaderEnricher();
    // each tailing flow must have its own instance of sequenceProvider as it is stateful and not thread-safe
    SequenceNumberHeaderEnricher sequenceProvider = new SequenceNumberHeaderEnricher();

    int groupSizeThreshold = 50;          // TODO move to properties
    int groupTimeout = 1_000;             // TODO move to properties

    /* When dealing with log message groups, some messages have to be resent to aggregator. Since the aggregator is
    capable of processing single message at a moment only, it is prepended with a queue channel that stores the incoming
    messages (including those to resend). This approach produces quite subtle situation - if the queue
    happens to be filled with some message(s) before another message returns there to be resent, the order of
    messages can be corrupted as the early arrived messages must leave the queue early as well (since the queue is of
    FIFO discipline). To avoid this, the queue is created as priority one. The priority is specified as simple
    sequence number (much like the one built in FileSplitter) and provided by dedicated header enricher. */
    PriorityBlockingQueue<Message<?>> queue = new PriorityBlockingQueue<>(100,
        Comparator.comparingLong(message -> message.getHeaders().get(SEQUENCE_NUMBER__HEADER, Long.class)));
    MessageChannel preAggregatorQueueChannel = queue(RECORD_AGGREGATOR_INPUT_CHANNEL, queue).get();

    RecordAggregatorConfigurer recordAggregatorConfigurer
        = new RecordAggregatorConfigurer(preAggregatorQueueChannel, groupSizeThreshold, groupTimeout);

    return IntegrationFlows
        .from(tailAdapter(new File(logPath)).id("tailSource"))
        .enrichHeaders(e -> e.headerFunction(LOG_TIMESTAMP_VALUE__HEADER, timestampExtractor::extractTimestamp))
        .enrichHeaders(e -> e.headerFunction(CORRELATION_ID, correlationProvider::obtainCorrelationId))
        .enrichHeaders(e -> e.headerFunction(RECORD_LEVEL__HEADER, this::detectRecordLevelSingle))
        .enrichHeaders(e -> e.headerFunction(SEQUENCE_NUMBER__HEADER, sequenceProvider::assignSequenceNumber))
        .channel(preAggregatorQueueChannel)
        .aggregate(recordAggregatorConfigurer::configure)
        .channel(channels -> channels.publishSubscribe(logPath))
        .get();
  }

  private RecordLevel detectRecordLevelSingle(Message<String> recordMessage) {
    if (!recordMessage.getHeaders().containsKey(LOG_TIMESTAMP_VALUE__HEADER)) {
      return null;
    }
    String recordFirstLine = recordMessage.getPayload();
    return Stream.of(RecordLevel.values())
        .filter(level -> !UNKNOWN.equals(level))
        .filter(level -> recordFirstLine.contains(level.name()))    // this is potential subject to change in future
        .findAny()
        .orElse(UNKNOWN);
  }

}
