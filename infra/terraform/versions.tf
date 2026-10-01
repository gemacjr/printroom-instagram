terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # Configure remote state before real use, e.g.:
  # backend "s3" {
  #   bucket       = "my-tf-state"
  #   key          = "printroom/prod.tfstate"
  #   region       = "us-east-1"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = var.name, Environment = var.environment, ManagedBy = "terraform" }
  }
}
