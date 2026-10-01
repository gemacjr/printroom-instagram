package com.instagram.follow;

import com.instagram.config.AppProperties;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Follows table: PK = followerId, SK = followeeId  -> "who do I follow" is a single-partition query.
 * GSI followee-index: PK = followeeId, SK = followerId -> "who follows me", used by feed fan-out.
 */
@Repository
public class FollowRepository {
    public static final String FOLLOWEE_INDEX = "followee-index";

    private final DynamoDbClient ddb;
    private final String followsTable;
    private final String usersTable;

    public FollowRepository(DynamoDbClient ddb, AppProperties props) {
        this.ddb = ddb;
        this.followsTable = props.tables().follows();
        this.usersTable = props.tables().users();
    }

    public record FollowPage(List<String> userIds, String lastKey) {}

    /**
     * Writes the follow edge and both denormalized counters in one transaction.
     *
     * @return false if the edge already existed (idempotent no-op)
     * @throws IllegalStateException if the followee does not exist
     */
    public boolean create(String followerId, String followeeId, long createdAt) {
        try {
            ddb.transactWriteItems(r -> r.transactItems(
                    TransactWriteItem.builder().put(Put.builder()
                            .tableName(followsTable)
                            .item(Map.of(
                                    "followerId", AttributeValue.fromS(followerId),
                                    "followeeId", AttributeValue.fromS(followeeId),
                                    "createdAt", AttributeValue.fromN(Long.toString(createdAt))))
                            .conditionExpression("attribute_not_exists(followerId)")
                            .build()).build(),
                    counterUpdate(followerId, "followingCount", 1),
                    counterUpdate(followeeId, "followerCount", 1)));
            return true;
        } catch (TransactionCanceledException e) {
            if (failedAt(e, 0)) return false;
            throw new IllegalStateException("Follow target does not exist", e);
        }
    }

    /** @return false if there was no edge to delete. */
    public boolean delete(String followerId, String followeeId) {
        try {
            ddb.transactWriteItems(r -> r.transactItems(
                    TransactWriteItem.builder().delete(Delete.builder()
                            .tableName(followsTable)
                            .key(key(followerId, followeeId))
                            .conditionExpression("attribute_exists(followerId)")
                            .build()).build(),
                    counterUpdate(followerId, "followingCount", -1),
                    counterUpdate(followeeId, "followerCount", -1)));
            return true;
        } catch (TransactionCanceledException e) {
            return false;
        }
    }

    public boolean exists(String followerId, String followeeId) {
        var item = ddb.getItem(r -> r.tableName(followsTable).key(key(followerId, followeeId))).item();
        return item != null && !item.isEmpty();
    }

    /** Users that {@code followerId} follows, ordered by followeeId. */
    public FollowPage findFollowees(String followerId, String afterFolloweeId, int limit) {
        var builder = QueryRequest.builder()
                .tableName(followsTable)
                .keyConditionExpression("followerId = :u")
                .expressionAttributeValues(Map.of(":u", AttributeValue.fromS(followerId)))
                .projectionExpression("followeeId")
                .limit(limit);
        if (afterFolloweeId != null) builder.exclusiveStartKey(key(followerId, afterFolloweeId));
        var resp = ddb.query(builder.build());
        List<String> ids = resp.items().stream().map(i -> i.get("followeeId").s()).toList();
        return new FollowPage(ids, lastKeyOrNull(resp.lastEvaluatedKey(), "followeeId"));
    }

    /** Users following {@code followeeId}, via the GSI. */
    public FollowPage findFollowers(String followeeId, String afterFollowerId, int limit) {
        var builder = QueryRequest.builder()
                .tableName(followsTable)
                .indexName(FOLLOWEE_INDEX)
                .keyConditionExpression("followeeId = :u")
                .expressionAttributeValues(Map.of(":u", AttributeValue.fromS(followeeId)))
                .limit(limit);
        if (afterFollowerId != null) builder.exclusiveStartKey(key(afterFollowerId, followeeId));
        var resp = ddb.query(builder.build());
        List<String> ids = resp.items().stream().map(i -> i.get("followerId").s()).toList();
        return new FollowPage(ids, lastKeyOrNull(resp.lastEvaluatedKey(), "followerId"));
    }

    /** Every followee id (used for celebrity merge + cold feed rebuild). */
    public List<String> findAllFolloweeIds(String followerId) {
        List<String> all = new ArrayList<>();
        String after = null;
        do {
            var page = findFollowees(followerId, after, 1000);
            all.addAll(page.userIds());
            after = page.lastKey();
        } while (after != null);
        return all;
    }

    /** Streams followers in batches — the fan-out worker's access pattern. */
    public void forEachFollowerBatch(String followeeId, int batchSize, Consumer<List<String>> consumer) {
        String after = null;
        do {
            var page = findFollowers(followeeId, after, batchSize);
            if (!page.userIds().isEmpty()) consumer.accept(page.userIds());
            after = page.lastKey();
        } while (after != null);
    }

    private TransactWriteItem counterUpdate(String userId, String attribute, int delta) {
        var update = Update.builder()
                .tableName(usersTable)
                .key(Map.of("userId", AttributeValue.fromS(userId)))
                .updateExpression("ADD #c :d")
                .expressionAttributeNames(Map.of("#c", attribute))
                .conditionExpression("attribute_exists(userId)");
        update.expressionAttributeValues(Map.of(":d", AttributeValue.fromN(Integer.toString(delta))));
        return TransactWriteItem.builder().update(update.build()).build();
    }

    private static Map<String, AttributeValue> key(String followerId, String followeeId) {
        return Map.of("followerId", AttributeValue.fromS(followerId),
                "followeeId", AttributeValue.fromS(followeeId));
    }

    private static String lastKeyOrNull(Map<String, AttributeValue> lastKey, String attr) {
        if (lastKey == null || lastKey.isEmpty()) return null;
        return lastKey.get(attr).s();
    }

    private static boolean failedAt(TransactionCanceledException e, int index) {
        if (!e.hasCancellationReasons()) return false;
        List<CancellationReason> reasons = e.cancellationReasons();
        return reasons.size() > index && "ConditionalCheckFailed".equals(reasons.get(index).code());
    }
}
