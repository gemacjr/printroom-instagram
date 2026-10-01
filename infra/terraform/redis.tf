resource "aws_elasticache_subnet_group" "redis" {
  name       = "${local.prefix}-redis"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_parameter_group" "redis" {
  name   = "${local.prefix}-redis7"
  family = "redis7"
  # Feeds have TTLs; evict least-recently-used volatile keys under memory pressure. Evicted feeds are
  # rebuilt from DynamoDB on next read (fan-out-on-read fallback).
  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }
}

# Primary + replica with automatic failover. For 500M DAU this becomes a cluster-mode-enabled group
# sharded by feed key.
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${local.prefix}-feeds"
  description                = "Precomputed feeds + celebrity registry"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  parameter_group_name       = aws_elasticache_parameter_group.redis.name
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.redis.id]
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
}
