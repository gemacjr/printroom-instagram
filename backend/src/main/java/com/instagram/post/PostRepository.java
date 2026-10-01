package com.instagram.post;

import com.instagram.common.Cursor;
import com.instagram.config.AppProperties;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PostRepository {
    private static final int BATCH_GET_LIMIT = 100;

    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<Post> table;
    private final DynamoDbIndex<Post> byAuthor;

    public PostRepository(DynamoDbEnhancedClient enhanced, AppProperties props) {
        this.enhanced = enhanced;
        this.table = enhanced.table(props.tables().posts(), TableSchema.fromBean(Post.class));
        this.byAuthor = table.index(Post.AUTHOR_INDEX);
    }

    public void create(Post post) {
        table.putItem(PutItemEnhancedRequest.builder(Post.class).item(post)
                .conditionExpression(Expression.builder().expression("attribute_not_exists(postId)").build())
                .build());
    }

    public Optional<Post> findById(String postId, boolean consistent) {
        return Optional.ofNullable(table.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(postId).build())
                .consistentRead(consistent).build()));
    }

    /** Batch-loads posts (feed hydration), chunked to 100 keys per BatchGetItem. */
    public Map<String, Post> findAllByIds(Collection<String> ids) {
        Map<String, Post> result = new HashMap<>();
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < unique.size(); i += BATCH_GET_LIMIT) {
            var batch = ReadBatch.builder(Post.class).mappedTableResource(table);
            unique.subList(i, Math.min(i + BATCH_GET_LIMIT, unique.size()))
                    .forEach(id -> batch.addGetItem(Key.builder().partitionValue(id).build()));
            enhanced.batchGetItem(BatchGetItemEnhancedRequest.builder().readBatches(batch.build()).build())
                    .resultsForTable(table)
                    .forEach(p -> result.put(p.getPostId(), p));
        }
        return result;
    }

    /**
     * Replaces the post only if it is still PROCESSING — makes publishing idempotent when the API request
     * and the media worker race to publish the same post.
     */
    public boolean updateIfProcessing(Post post) {
        try {
            table.updateItem(UpdateItemEnhancedRequest.builder(Post.class).item(post)
                    .conditionExpression(Expression.builder()
                            .expression("#s = :processing")
                            .putExpressionName("#s", "status")
                            .putExpressionValue(":processing", AttributeValue.fromS(PostStatus.PROCESSING.name()))
                            .build())
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public void delete(String postId) {
        table.deleteItem(Key.builder().partitionValue(postId).build());
    }

    /**
     * Newest-first published posts by an author, strictly after {@code before} in feed order.
     * Uses the sparse author-published GSI.
     */
    public List<Post> findPublishedByAuthor(String authorId, Cursor before, int limit) {
        QueryConditional condition = before == null
                ? QueryConditional.keyEqualTo(k -> k.partitionValue(authorId))
                : QueryConditional.sortLessThanOrEqualTo(k -> k.partitionValue(authorId).sortValue(before.timestamp()));
        var request = QueryEnhancedRequest.builder()
                .queryConditional(condition)
                .scanIndexForward(false)
                .limit(limit + 1)
                .build();
        List<Post> out = new ArrayList<>(limit);
        for (var page : byAuthor.query(request)) {
            for (Post p : page.items()) {
                if (before != null && !before.isAfter(p.getPublishedAt(), p.getPostId())) continue;
                out.add(p);
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }
}
