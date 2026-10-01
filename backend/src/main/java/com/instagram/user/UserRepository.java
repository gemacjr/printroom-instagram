package com.instagram.user;

import com.instagram.config.AppProperties;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserRepository {
    static final int BATCH_GET_LIMIT = 100;

    private final DynamoDbClient ddb;
    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<User> users;
    private final String usersTable;
    private final String usernamesTable;

    public UserRepository(DynamoDbClient ddb, DynamoDbEnhancedClient enhanced, AppProperties props) {
        this.ddb = ddb;
        this.enhanced = enhanced;
        this.usersTable = props.tables().users();
        this.usernamesTable = props.tables().usernames();
        this.users = enhanced.table(usersTable, TableSchema.fromBean(User.class));
    }

    /**
     * Creates the user and claims the username atomically. DynamoDB GSIs can't enforce uniqueness,
     * so a dedicated usernames table with a conditional put inside a transaction does.
     *
     * @return false if the username is already taken
     */
    public boolean createWithUniqueUsername(User user) {
        Map<String, AttributeValue> userItem = TableSchema.fromBean(User.class).itemToMap(user, true);
        Map<String, AttributeValue> claim = Map.of(
                "username", AttributeValue.fromS(user.getUsername()),
                "userId", AttributeValue.fromS(user.getUserId()));
        try {
            ddb.transactWriteItems(r -> r.transactItems(
                    TransactWriteItem.builder().put(Put.builder()
                            .tableName(usernamesTable).item(claim)
                            .conditionExpression("attribute_not_exists(username)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder()
                            .tableName(usersTable).item(userItem)
                            .conditionExpression("attribute_not_exists(userId)").build()).build()));
            return true;
        } catch (TransactionCanceledException e) {
            return false;
        }
    }

    public Optional<User> findById(String userId) {
        return Optional.ofNullable(users.getItem(Key.builder().partitionValue(userId).build()));
    }

    public Optional<String> findUserIdByUsername(String username) {
        var item = ddb.getItem(r -> r.tableName(usernamesTable)
                .key(Map.of("username", AttributeValue.fromS(username)))).item();
        if (item == null || item.isEmpty()) return Optional.empty();
        return Optional.of(item.get("userId").s());
    }

    public Optional<User> findByUsername(String username) {
        return findUserIdByUsername(username).flatMap(this::findById);
    }

    /** Batch-loads users, chunked to DynamoDB's 100-key BatchGetItem limit. */
    public Map<String, User> findAllByIds(Collection<String> ids) {
        Map<String, User> result = new HashMap<>();
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < unique.size(); i += BATCH_GET_LIMIT) {
            var chunk = unique.subList(i, Math.min(i + BATCH_GET_LIMIT, unique.size()));
            var batch = ReadBatch.builder(User.class).mappedTableResource(users);
            chunk.forEach(id -> batch.addGetItem(Key.builder().partitionValue(id).build()));
            enhanced.batchGetItem(BatchGetItemEnhancedRequest.builder().readBatches(batch.build()).build())
                    .resultsForTable(users)
                    .forEach(u -> result.put(u.getUserId(), u));
        }
        return result;
    }

    public void updateProfile(String userId, String displayName, String bio) {
        var values = new HashMap<String, AttributeValue>();
        var sets = new ArrayList<String>();
        if (displayName != null) {
            sets.add("displayName = :dn");
            values.put(":dn", AttributeValue.fromS(displayName));
        }
        if (bio != null) {
            sets.add("bio = :bio");
            values.put(":bio", AttributeValue.fromS(bio));
        }
        if (sets.isEmpty()) return;
        ddb.updateItem(UpdateItemRequest.builder()
                .tableName(usersTable)
                .key(Map.of("userId", AttributeValue.fromS(userId)))
                .updateExpression("SET " + String.join(", ", sets))
                .conditionExpression("attribute_exists(userId)")
                .expressionAttributeValues(values)
                .build());
    }

    /**
     * Atomically adds {@code delta} to a counter attribute and returns the new value.
     * Counters never go below zero (guarded by condition when decrementing).
     */
    public long incrementCounter(String userId, String attribute, long delta) {
        var req = UpdateItemRequest.builder()
                .tableName(usersTable)
                .key(Map.of("userId", AttributeValue.fromS(userId)))
                .updateExpression("ADD #c :d")
                .expressionAttributeNames(Map.of("#c", attribute))
                .expressionAttributeValues(delta < 0
                        ? Map.of(":d", AttributeValue.fromN(Long.toString(delta)),
                                 ":min", AttributeValue.fromN(Long.toString(-delta)))
                        : Map.of(":d", AttributeValue.fromN(Long.toString(delta))))
                .returnValues(ReturnValue.UPDATED_NEW);
        if (delta < 0) req.conditionExpression("#c >= :min");
        else req.conditionExpression("attribute_exists(userId)");
        try {
            return Long.parseLong(ddb.updateItem(req.build()).attributes().get(attribute).n());
        } catch (ConditionalCheckFailedException e) {
            return 0;
        }
    }

    /**
     * Prefix search over usernames. A Scan is fine for a demo dataset; at Instagram scale this would be
     * served by OpenSearch fed from a DynamoDB stream.
     */
    public List<String> searchUserIdsByUsernamePrefix(String prefix, int limit) {
        List<String> ids = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            var resp = ddb.scan(ScanRequest.builder()
                    .tableName(usernamesTable)
                    .filterExpression("begins_with(username, :p)")
                    .expressionAttributeValues(Map.of(":p", AttributeValue.fromS(prefix)))
                    .exclusiveStartKey(startKey)
                    .build());
            resp.items().forEach(i -> ids.add(i.get("userId").s()));
            startKey = resp.hasLastEvaluatedKey() && !resp.lastEvaluatedKey().isEmpty() ? resp.lastEvaluatedKey() : null;
        } while (startKey != null && ids.size() < limit);
        return ids.size() > limit ? ids.subList(0, limit) : ids;
    }
}
