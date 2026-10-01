package com.instagram.worker;

import com.instagram.support.TestFixtures;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SqsWorkerTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final Message message = Message.builder().messageId("1").body("x").receiptHandle("rh").build();

    private SqsWorker worker(boolean fail) {
        return new SqsWorker(sqs, TestFixtures.props(), "q", 1) {
            @Override
            protected void handle(String body) {
                if (fail) throw new RuntimeException("boom");
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesMessageAfterSuccessfulHandling() {
        worker(false).process("url", message);
        verify(sqs).deleteMessage(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesMessageForRetryWhenHandlerFails() {
        worker(true).process("url", message);
        verify(sqs, never()).deleteMessage(any(Consumer.class));
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void disabledWorkerDoesNotPoll() {
        var w = worker(false); // TestFixtures has workers disabled
        w.start();
        org.assertj.core.api.Assertions.assertThat(w.isRunning()).isFalse();
    }
}
