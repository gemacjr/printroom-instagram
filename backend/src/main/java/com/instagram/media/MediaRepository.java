package com.instagram.media;

import com.instagram.config.AppProperties;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MediaRepository {
    private static final TableSchema<Media> SCHEMA = TableSchema.fromBean(Media.class);

    private final DynamoDbClient ddb;
    private final DynamoDbTable<Media> table;
    private final String tableName;

    public MediaRepository(DynamoDbClient ddb, DynamoDbEnhancedClient enhanced, AppProperties props) {
        this.ddb = ddb;
        this.tableName = props.tables().media();
        this.table = enhanced.table(tableName, SCHEMA);
    }

    public void create(Media media) {
        table.putItem(PutItemEnhancedRequest.builder(Media.class).item(media)
                .conditionExpression(Expression.builder().expression("attribute_not_exists(mediaId)").build())
                .build());
    }

    public Optional<Media> findById(String mediaId) {
        return Optional.ofNullable(table.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(mediaId).build())
                .consistentRead(true).build()));
    }

    /**
     * Strongly consistent reads of several media items. Posts carry at most 10 items, so a handful of
     * GetItems is cheaper and simpler than a BatchGet (which can't be strongly consistent per-key in the
     * enhanced client API without extra plumbing).
     */
    public Map<String, Media> findAllByIds(Collection<String> ids) {
        Map<String, Media> result = new HashMap<>();
        for (String id : ids) findById(id).ifPresent(m -> result.put(id, m));
        return result;
    }

    /**
     * Attaches media to a post. Guarded so media can only be attached once, only by its owner, and not
     * after it has failed processing.
     *
     * @return the updated item, or empty if the guard failed
     */
    public Optional<Media> attachToPost(String mediaId, String ownerId, String postId) {
        try {
            var resp = ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(mediaId))
                    .updateExpression("SET postId = :p")
                    .conditionExpression("attribute_exists(mediaId) AND ownerId = :o "
                            + "AND attribute_not_exists(postId) AND #s <> :failed")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":p", AttributeValue.fromS(postId),
                            ":o", AttributeValue.fromS(ownerId),
                            ":failed", AttributeValue.fromS(MediaStatus.FAILED.name())))
                    .returnValues(ReturnValue.ALL_NEW)
                    .build());
            return Optional.of(SCHEMA.mapToItem(resp.attributes()));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    public void detachFromPost(String mediaId, String postId) {
        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(mediaId))
                    .updateExpression("REMOVE postId")
                    .conditionExpression("postId = :p")
                    .expressionAttributeValues(Map.of(":p", AttributeValue.fromS(postId)))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // already detached / attached elsewhere
        }
    }

    /**
     * PENDING_UPLOAD -> READY. Conditional so duplicate S3 events (SQS is at-least-once) are no-ops.
     *
     * @return the updated item (including any postId attached concurrently), or empty if already processed
     */
    public Optional<Media> markReady(String mediaId, String displayKey, Integer width, Integer height,
                                     long actualSize) {
        var values = new HashMap<String, AttributeValue>();
        values.put(":ready", AttributeValue.fromS(MediaStatus.READY.name()));
        values.put(":pending", AttributeValue.fromS(MediaStatus.PENDING_UPLOAD.name()));
        values.put(":dk", AttributeValue.fromS(displayKey));
        values.put(":sz", AttributeValue.fromN(Long.toString(actualSize)));
        List<String> sets = new ArrayList<>(List.of("#s = :ready", "displayKey = :dk", "sizeBytes = :sz"));
        if (width != null && height != null) {
            values.put(":w", AttributeValue.fromN(width.toString()));
            values.put(":h", AttributeValue.fromN(height.toString()));
            sets.add("width = :w");
            sets.add("height = :h");
        }
        return transition(mediaId, "SET " + String.join(", ", sets) + " REMOVE uploadId", values);
    }

    public Optional<Media> markFailed(String mediaId, String reason) {
        return transition(mediaId, "SET #s = :failed, failureReason = :r REMOVE uploadId", Map.of(
                ":failed", AttributeValue.fromS(MediaStatus.FAILED.name()),
                ":pending", AttributeValue.fromS(MediaStatus.PENDING_UPLOAD.name()),
                ":r", AttributeValue.fromS(reason)));
    }

    public void delete(String mediaId) {
        table.deleteItem(Key.builder().partitionValue(mediaId).build());
    }

    private Optional<Media> transition(String mediaId, String updateExpression, Map<String, AttributeValue> values) {
        try {
            var resp = ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(mediaId))
                    .updateExpression(updateExpression)
                    .conditionExpression("#s = :pending")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_NEW)
                    .build());
            return Optional.of(SCHEMA.mapToItem(resp.attributes()));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    private static Map<String, AttributeValue> key(String mediaId) {
        return Map.of("mediaId", AttributeValue.fromS(mediaId));
    }
}
