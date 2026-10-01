variable "name" {
  description = "Resource name prefix"
  type        = string
  default     = "printroom"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "image_tag" {
  description = "Tag of the api/web images in ECR to deploy"
  type        = string
  default     = "latest"
}

variable "certificate_arn" {
  description = "ACM certificate for HTTPS on the ALB. Empty = HTTP only (not for real production)."
  type        = string
  default     = ""
}

variable "app_origin" {
  description = "Public origin of the web app (e.g. https://printroom.example.com). Defaults to the ALB DNS name."
  type        = string
  default     = ""
}

variable "celebrity_threshold" {
  description = "Follower count at which an author's posts switch from fan-out-on-write to fan-out-on-read"
  type        = number
  default     = 100000
}

variable "api_desired_count" {
  type    = number
  default = 2
}

variable "worker_desired_count" {
  type    = number
  default = 2
}

variable "web_desired_count" {
  type    = number
  default = 2
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}
