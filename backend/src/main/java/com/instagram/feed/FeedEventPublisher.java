package com.instagram.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instagram.config.AppProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.concurrent.atomic.AtomicReference;

/** Enqueues fan-out work so post creation latency doesn't depend on follower count. */
@Component
public class FeedEventPublisher {
    private final SqsClient sqs;
    private final ObjectMapper mapper;
    private final String queueName;
    private final AtomicReference<String> queueUrl = new AtomicReference<>();

    public FeedEventPublisher(SqsClient sqs, ObjectMapper mapper, AppProperties props) {
        this.sqs = sqs;
        this.mapper = mapper;
        this.queueName = props.queues().feedFanout();
    }

    public void publish(FanoutEvent event) {
        try {
            String body = mapper.writeValueAsString(event);
            sqs.sendMessage(r -> r.queueUrl(queueUrl()).messageBody(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String queueUrl() {
        return queueUrl.updateAndGet(u -> u != null ? u
                : sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl());
    }
}
