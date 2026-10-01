package com.instagram.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instagram.config.AppProperties;
import com.instagram.feed.FanoutEvent;
import com.instagram.feed.FanoutService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Wires the two queue consumers. In prod these run in the worker ECS service (WORKERS_ENABLED=true). */
@Configuration
public class Workers {

    @Bean
    SqsWorker mediaEventsWorker(SqsClient sqs, AppProperties props, MediaEventHandler handler) {
        return new SqsWorker(sqs, props, props.queues().mediaEvents(), 2) {
            @Override
            protected void handle(String body) throws Exception {
                handler.handle(body);
            }
        };
    }

    @Bean
    SqsWorker feedFanoutWorker(SqsClient sqs, AppProperties props, FanoutService fanout, ObjectMapper mapper) {
        return new SqsWorker(sqs, props, props.queues().feedFanout(), 4) {
            @Override
            protected void handle(String body) throws Exception {
                fanout.fanout(mapper.readValue(body, FanoutEvent.class));
            }
        };
    }
}
