locals {
  scheme     = var.certificate_arn == "" ? "http" : "https"
  app_origin = var.app_origin != "" ? var.app_origin : "${local.scheme}://${aws_lb.main.dns_name}"

  backend_env = [
    { name = "AWS_REGION", value = var.region },
    { name = "TABLE_USERS", value = aws_dynamodb_table.users.name },
    { name = "TABLE_USERNAMES", value = aws_dynamodb_table.usernames.name },
    { name = "TABLE_POSTS", value = aws_dynamodb_table.posts.name },
    { name = "TABLE_FOLLOWS", value = aws_dynamodb_table.follows.name },
    { name = "TABLE_MEDIA", value = aws_dynamodb_table.media.name },
    { name = "MEDIA_BUCKET", value = aws_s3_bucket.media.bucket },
    { name = "MEDIA_CDN_BASE_URL", value = "https://${aws_cloudfront_distribution.media.domain_name}" },
    { name = "QUEUE_MEDIA_EVENTS", value = aws_sqs_queue.main["media_events"].name },
    { name = "QUEUE_FEED_FANOUT", value = aws_sqs_queue.main["feed_fanout"].name },
    { name = "REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
    { name = "REDIS_SSL", value = "true" },
    { name = "FEED_CELEBRITY_THRESHOLD", value = tostring(var.celebrity_threshold) },
    { name = "CORS_ALLOWED_ORIGINS", value = local.app_origin },
    { name = "JAVA_OPTS", value = "-XX:MaxRAMPercentage=75 -Djava.awt.headless=true" },
  ]
  backend_secrets = [{ name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt.arn }]
}

# ---------- Images ----------

resource "aws_ecr_repository" "repo" {
  for_each             = toset(["api", "web"])
  name                 = "${local.prefix}-${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = false
  image_scanning_configuration { scan_on_push = true }
}

# ---------- Secrets ----------

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "jwt" {
  name = "${local.prefix}/jwt-secret"
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt.result
}

# ---------- IAM ----------

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = aws_secretsmanager_secret.jwt.arn }]
  })
}

# Least-privilege runtime role shared by api + worker.
resource "aws_iam_role" "backend_task" {
  name               = "${local.prefix}-backend-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "backend_task" {
  role = aws_iam_role.backend_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Tables"
        Effect = "Allow"
        Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem",
        "dynamodb:Query", "dynamodb:Scan", "dynamodb:BatchGetItem", "dynamodb:ConditionCheckItem"]
        Resource = flatten([for t in [aws_dynamodb_table.users, aws_dynamodb_table.usernames, aws_dynamodb_table.posts,
        aws_dynamodb_table.follows, aws_dynamodb_table.media] : [t.arn, "${t.arn}/index/*"]])
      },
      {
        Sid      = "Media"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
        Resource = "${aws_s3_bucket.media.arn}/media/*"
      },
      {
        Sid      = "Queues"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueUrl", "sqs:ChangeMessageVisibility"]
        Resource = [for q in aws_sqs_queue.main : q.arn]
      },
    ]
  })
}

# ---------- Cluster, logs ----------

resource "aws_ecs_cluster" "main" {
  name = local.prefix
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = toset(["api", "worker", "web"])
  name              = "/ecs/${local.prefix}/${each.key}"
  retention_in_days = 30
}

# ---------- Task definitions ----------

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.prefix}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.backend_task.arn
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name         = "api"
    image        = "${aws_ecr_repository.repo["api"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080 }]
    environment  = concat(local.backend_env, [{ name = "WORKERS_ENABLED", value = "false" }])
    secrets      = local.backend_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options   = { awslogs-group = aws_cloudwatch_log_group.svc["api"].name, awslogs-region = var.region, awslogs-stream-prefix = "api" }
    }
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.prefix}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.backend_task.arn
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name        = "worker"
    image       = "${aws_ecr_repository.repo["api"].repository_url}:${var.image_tag}"
    essential   = true
    environment = concat(local.backend_env, [{ name = "WORKERS_ENABLED", value = "true" }])
    secrets     = local.backend_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options   = { awslogs-group = aws_cloudwatch_log_group.svc["worker"].name, awslogs-region = var.region, awslogs-stream-prefix = "worker" }
    }
  }])
}

resource "aws_ecs_task_definition" "web" {
  family                   = "${local.prefix}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }
  container_definitions = jsonencode([{
    name         = "web"
    image        = "${aws_ecr_repository.repo["web"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 3000 }]
    # The ALB routes /api/* to the API before it reaches Next.js; this is only a fallback.
    environment = [{ name = "API_URL", value = "http://${aws_lb.main.dns_name}" }]
    logConfiguration = {
      logDriver = "awslogs"
      options   = { awslogs-group = aws_cloudwatch_log_group.svc["web"].name, awslogs-region = var.region, awslogs-stream-prefix = "web" }
    }
  }])
}

# ---------- Services ----------

resource "aws_ecs_service" "api" {
  name                              = "api"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.api.arn
  desired_count                     = var.api_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60
  network_configuration {
    subnets         = module.vpc.private_subnets
    security_groups = [aws_security_group.services.id]
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  lifecycle { ignore_changes = [desired_count] }
  depends_on = [aws_lb_listener.http]
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_desired_count
  launch_type     = "FARGATE"
  network_configuration {
    subnets         = module.vpc.private_subnets
    security_groups = [aws_security_group.services.id]
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  lifecycle { ignore_changes = [desired_count] }
}

resource "aws_ecs_service" "web" {
  name                              = "web"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.web.arn
  desired_count                     = var.web_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 30
  network_configuration {
    subnets         = module.vpc.private_subnets
    security_groups = [aws_security_group.services.id]
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web"
    container_port   = 3000
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  lifecycle { ignore_changes = [desired_count] }
  depends_on = [aws_lb_listener.http]
}

# ---------- Autoscaling ----------

resource "aws_appautoscaling_target" "svc" {
  for_each           = { api = [2, 20], worker = [1, 20], web = [2, 10] }
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${each.key}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = each.value[0]
  max_capacity       = each.value[1]
  depends_on         = [aws_ecs_service.api, aws_ecs_service.worker, aws_ecs_service.web]
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = toset(["api", "web"])
  name               = "${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = "ecs"
  resource_id        = aws_appautoscaling_target.svc[each.key].resource_id
  scalable_dimension = "ecs:service:DesiredCount"
  target_tracking_scaling_policy_configuration {
    target_value = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}

# Workers scale on fan-out backlog: a burst of posts should drain within the 2-minute staleness budget.
resource "aws_appautoscaling_policy" "worker_backlog" {
  name               = "worker-fanout-backlog"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = "ecs"
  resource_id        = aws_appautoscaling_target.svc["worker"].resource_id
  scalable_dimension = "ecs:service:DesiredCount"
  target_tracking_scaling_policy_configuration {
    target_value = 500
    customized_metric_specification {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesVisible"
      statistic   = "Average"
      dimensions {
        name  = "QueueName"
        value = aws_sqs_queue.main["feed_fanout"].name
      }
    }
  }
}
