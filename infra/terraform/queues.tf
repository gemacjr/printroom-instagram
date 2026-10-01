locals {
  queues = {
    media_events = "${local.prefix}-media-events"
    feed_fanout  = "${local.prefix}-feed-fanout"
  }
}

resource "aws_sqs_queue" "dlq" {
  for_each                  = local.queues
  name                      = "${each.value}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "main" {
  for_each                   = local.queues
  name                       = each.value
  visibility_timeout_seconds = 120
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = 5
  })
}

# Let the media bucket publish ObjectCreated events.
resource "aws_sqs_queue_policy" "media_events" {
  queue_url = aws_sqs_queue.main["media_events"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.main["media_events"].arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_s3_bucket.media.arn } }
    }]
  })
}

resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each            = local.queues
  alarm_name          = "${each.value}-dlq-not-empty"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dlq[each.key].name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_description   = "Messages failed processing 5 times and landed in the DLQ"
}
