package com.instagram.worker;

import com.instagram.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Minimal long-polling SQS consumer. A message is deleted only after its handler succeeds; failures are
 * left on the queue to reappear after the visibility timeout, and after maxReceiveCount the queue's
 * redrive policy moves them to a DLQ. Handlers must therefore be idempotent.
 */
public abstract class SqsWorker implements SmartLifecycle {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final SqsClient sqs;
    private final String queueName;
    private final int waitSeconds;
    private final boolean enabled;
    private final int concurrency;
    private volatile boolean running;
    private ExecutorService executor;

    protected SqsWorker(SqsClient sqs, AppProperties props, String queueName, int concurrency) {
        this.sqs = sqs;
        this.queueName = queueName;
        this.waitSeconds = props.workers().pollWaitSeconds();
        this.enabled = props.workers().enabled();
        this.concurrency = concurrency;
    }

    /** Process one message body. Throw to have the message retried. */
    protected abstract void handle(String body) throws Exception;

    @Override
    public void start() {
        if (!enabled) {
            log.info("Worker for {} disabled", queueName);
            return;
        }
        running = true;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < concurrency; i++) executor.submit(this::pollLoop);
        log.info("Started {} poller(s) on {}", concurrency, queueName);
    }

    private void pollLoop() {
        String queueUrl = null;
        while (running) {
            try {
                if (queueUrl == null) queueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
                final String url = queueUrl;
                var messages = sqs.receiveMessage(r -> r.queueUrl(url)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(waitSeconds)).messages();
                for (Message m : messages) process(url, m);
            } catch (Exception e) {
                if (!running) return;
                log.warn("Polling {} failed: {}", queueName, e.toString());
                sleepQuietly(2000);
            }
        }
    }

    void process(String queueUrl, Message message) {
        try {
            handle(message.body());
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (Exception e) {
            log.error("Handler failed for message {} on {}; will retry", message.messageId(), queueName, e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
