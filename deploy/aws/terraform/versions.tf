terraform {
  required_version = ">= 1.16.0, < 1.17.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.64.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Configure the bucket/key/region at init time. CI uses -backend=false.
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    }
  }
}
