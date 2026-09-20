package dev.manuelantunes.axonposts.infrastructure.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

@Named("axon-sqs")
@ApplicationScoped
public class AxonSqsLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private final SqsChannelIngress ingress;

    AxonSqsLambda(SqsChannelIngress ingress) {
        this.ingress = ingress;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return ingress.ingest(event);
    }
}
