output "app_url" {
  value = local.app_origin
}

output "media_cdn_url" {
  value = "https://${aws_cloudfront_distribution.media.domain_name}"
}

output "ecr_api_repository" {
  value = aws_ecr_repository.repo["api"].repository_url
}

output "ecr_web_repository" {
  value = aws_ecr_repository.repo["web"].repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "media_bucket" {
  value = aws_s3_bucket.media.bucket
}
