# Architecture

An implementation of the Hello Interview "Design Instagram" breakdown. The public part of that
article fixes the requirements and scale; the deep-dive section is behind a paywall. The designs
below are the standard answers to its three stated deep dives (feed latency, large media, scale),
and where a choice is this project's own, it says so.

## Requirements

**Functional**
1. Create posts with photos, videos and a caption
2. Follow users
3. See a chronological feed of posts from followed users

**Non-functional** (500M DAU, 100M posts/day)
- Availability over consistency: a new post may take up to ~2 minutes to appear in followers' feeds
- Feed reads < 500 ms
- Photos up to 8 MB, videos up to 4 GB; media renders fast worldwide
- Horizontally scalable at every tier

## Components

```
                       ┌──────────── CloudFront (media CDN) ◄──── S3 GET via OAC
Browser ─── ALB ──┬── /api/* ──► API service (Spring Boot, ECS)
   │              └── /*     ──► Web (Next.js, ECS)
   │                               │  │  │
   │  presigned PUT / multipart    │  │  └──► Redis (ElastiCache): feed:{userId} ZSETs, celebrities SET
   └──────────────────────────► S3 │  └─────► DynamoDB: users, usernames, posts, follows, media
                                │  └────────► SQS feed-fanout ──► Worker (fan-out on write)
                                └── ObjectCreated ──► SQS media-events ──► Worker (process media, publish post)
```

The API and worker run from the same image. `WORKERS_ENABLED` decides whether the SQS pollers
start, so each tier scales on its own: the API on CPU, workers on queue backlog.

| Concern | Local | AWS |
|---|---|---|
| Tables | LocalStack DynamoDB | DynamoDB on-demand, PITR |
| Media | LocalStack S3 | S3 (private) + CloudFront OAC |
| Queues | LocalStack SQS (+ DLQ) | SQS (+ DLQ + alarm) |
| Feed cache | Redis container | ElastiCache Redis, multi-AZ |
| Compute | docker compose | ECS Fargate behind an ALB |

## Data model (DynamoDB)

| Table | Key | Indexes | Why |
|---|---|---|---|
| users | `userId` | – | Profile + denormalized counters (atomic `ADD`) |
| usernames | `username` | – | Uniqueness via conditional put inside a transaction (a GSI can't enforce it) |
| posts | `postId` | `author-published-index` (`authorId`, `publishedAt`) | **Sparse** index: only published posts carry `publishedAt`, so processing posts never show up in timelines |
| follows | `followerId` + `followeeId` | `followee-index` (`followeeId`, `followerId`) | "Who do I follow" = one partition; "who follows X" = the GSI the fan-out worker streams |
| media | `mediaId` | – | Upload/processing state machine, separate from posts |

Follow/unfollow writes the edge and both counters in one `TransactWriteItems` call, so counts never drift.

## Upload flow (large media)

1. `POST /api/media/uploads {contentType, sizeBytes}`: validates type and limits (8 MB images, 4 GB videos).
   - ≤ 100 MB: one presigned `PUT` with **Content-Type and Content-Length signed**, so the client can't upload something else.
   - Larger: `CreateMultipartUpload` plus one presigned URL per 100 MB part (respecting S3's 5 MB minimum and 10k-part maximum). The browser uploads four parts in parallel and then calls `POST /api/media/{id}/complete` with the ETags.
2. The bytes go **browser → S3 directly**; the API never proxies media.
3. S3 `ObjectCreated` (filtered to `*/original`) → SQS → `MediaEventHandler`:
   the image is decoded and re-encoded to a 1080px JPEG (this also strips EXIF); video passes through
   (in production: MediaConvert → HLS). The media becomes `READY` or `FAILED`.
4. `POST /api/posts {caption, mediaIds}` attaches media with a conditional update (owner only, never
   reused). The post publishes immediately if all media is READY; otherwise it stays `PROCESSING` and
   the worker publishes it later.
   - **Race-safety:** the API re-checks after attaching, and the worker re-checks after marking READY.
     Publishing is a conditional `status = PROCESSING` update, so exactly one side wins and the
     fan-out event is sent once.
5. An S3 lifecycle rule aborts abandoned multipart uploads after 2 days.

## Feed (hybrid fan-out)

**Write path**: when a post publishes, a `FanoutEvent` goes to SQS. The worker adds the post to the
author's own feed, then:
- If followers < `FEED_CELEBRITY_THRESHOLD` (default 100k): stream followers from `followee-index` in
  batches of 100 and `ZADD feed:{followerId} publishedAt postId`, trimmed to 500 entries. It
  pipelines `EXISTS` then `ZADD`, so only *materialized* feeds are touched.
- Otherwise it skips fan-out. Writing one post into 100M+ feeds would take minutes.

**Read path** (`GET /api/feed?cursor&limit`):
1. Load the followee ids and intersect them with the Redis `celebrities` set (`SMISMEMBER`).
2. If `feed:{me}` is missing (new user, evicted after 7 days of inactivity, Redis failover), rebuild
   it from DynamoDB: **fan-out on read as a fallback**.
3. Fetch `limit+1` entries from the cache, and `limit+1` from each followed celebrity's
   `author-published-index` in parallel (virtual threads).
4. Merge newest-first, de-duplicate, take `limit`, then batch-get posts and authors.
5. Deleted or unpublished ids are dropped here (**lazy delete**), which avoids fanning deletes out to followers.

Pagination is keyset-based: an opaque cursor encodes `(publishedAt, postId)`, where `postId` breaks
ties, so pages never overlap or skip, even when new posts arrive between requests.

Follow backfills the followee's latest 20 posts into your feed, and unfollow removes them.

## Deep-dive knobs

| Knob | Where | Default |
|---|---|---|
| Celebrity threshold | `FEED_CELEBRITY_THRESHOLD` | 100000 (compose: 3, so the path is testable) |
| Cached feed length | `app.feed.max-cached-entries` | 500 |
| Feed TTL | `RedisFeedCache.FEED_TTL` | 7 days |
| Multipart threshold / part size | `app.media.multipart-*` | 100 MB / 100 MB |
| Queue retries before DLQ | SQS redrive | 5 |

## Scaling to 500M DAU (beyond this repo)

- Redis in cluster mode, sharded by `feed:{userId}`. 500M users × 500 entries × ~50 B ≈ 12 TB, which
  is why feeds expire for inactive users.
- DynamoDB on-demand partitions scale automatically. Hot celebrities are read through the author
  index, which can sit behind a short-TTL cache for their latest posts.
- Username search moves to OpenSearch, fed from a DynamoDB stream (`UserRepository` uses a Scan for now).
- Video goes through MediaConvert to produce adaptive HLS, served by CloudFront.
