# Same schema as localstack/init/ready.d/01-init-aws.sh.
locals {
  table_prefix = "${local.prefix}-"
}

resource "aws_dynamodb_table" "users" {
  name                        = "${local.table_prefix}users"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "userId"
  deletion_protection_enabled = true

  attribute {
    name = "userId"
    type = "S"
  }
  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }
}

resource "aws_dynamodb_table" "usernames" {
  name                        = "${local.table_prefix}usernames"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "username"
  deletion_protection_enabled = true

  attribute {
    name = "username"
    type = "S"
  }
  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }
}

resource "aws_dynamodb_table" "posts" {
  name                        = "${local.table_prefix}posts"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "postId"
  deletion_protection_enabled = true

  attribute {
    name = "postId"
    type = "S"
  }
  attribute {
    name = "authorId"
    type = "S"
  }
  attribute {
    name = "publishedAt"
    type = "N"
  }

  # Sparse: only PUBLISHED posts carry publishedAt.
  global_secondary_index {
    name            = "author-published-index"
    projection_type = "ALL"
    key_schema {
      attribute_name = "authorId"
      key_type       = "HASH"
    }
    key_schema {
      attribute_name = "publishedAt"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }
}

resource "aws_dynamodb_table" "follows" {
  name                        = "${local.table_prefix}follows"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "followerId"
  range_key                   = "followeeId"
  deletion_protection_enabled = true

  attribute {
    name = "followerId"
    type = "S"
  }
  attribute {
    name = "followeeId"
    type = "S"
  }

  global_secondary_index {
    name            = "followee-index"
    projection_type = "KEYS_ONLY"
    key_schema {
      attribute_name = "followeeId"
      key_type       = "HASH"
    }
    key_schema {
      attribute_name = "followerId"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }
}

resource "aws_dynamodb_table" "media" {
  name                        = "${local.table_prefix}media"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "mediaId"
  deletion_protection_enabled = true

  attribute {
    name = "mediaId"
    type = "S"
  }
  point_in_time_recovery { enabled = true }
  server_side_encryption { enabled = true }
}
