package com.charitha.transactions.recovery;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterIndexer {
    private final DeadLetterStore store;
    private final String sourceTopic;

    public DeadLetterIndexer(DeadLetterStore store,
                             @Value("${topics.payment-created}") String sourceTopic) {
        this.store = store;
        this.sourceTopic = sourceTopic;
    }

    @KafkaListener(
            topics = "${topics.payment-created}.DLT",
            groupId = "${dlt.indexer.group-id:transaction-service-dlt-indexer}"
    )
    public void index(ConsumerRecord<String, String> record) {
        store.index(sourceTopic, record);
    }
}
