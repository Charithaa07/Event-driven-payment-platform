variable "project_name" {
  description = "Stable project prefix used in AWS resource names."
  type        = string
  default     = "payment-platform"
}

variable "environment" {
  description = "Deployment environment name."
  type        = string
  default     = "prod"
}

variable "aws_region" {
  description = "AWS region for the platform."
  type        = string
  default     = "us-west-2"
}

variable "vpc_cidr" {
  description = "CIDR for the application VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "kubernetes_version" {
  description = "Amazon EKS Kubernetes version."
  type        = string
  default     = "1.36"
}

variable "eks_public_access_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint. Tighten for shared environments or use a private runner."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "node_instance_types" {
  description = "Managed node group instance types."
  type        = list(string)
  default     = ["t3.large"]
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 6
}

variable "rds_instance_class" {
  description = "Instance class used by each service-owned PostgreSQL database."
  type        = string
  default     = "db.t4g.micro"
}

variable "rds_multi_az" {
  description = "Enable synchronous Multi-AZ standby for the service databases."
  type        = bool
  default     = true
}

variable "rds_deletion_protection" {
  type    = bool
  default = true
}

variable "rds_skip_final_snapshot" {
  description = "Set false in long-lived production accounts."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "GitHub repository allowed to federate into the deployment role."
  type        = string
  default     = "Charithaa07/Event-driven-payment-platform"
}

variable "github_environment" {
  description = "Protected GitHub Environment allowed to assume the AWS deployment role."
  type        = string
  default     = "production"
}

variable "create_github_oidc_provider" {
  description = "Create the account-level GitHub Actions OIDC provider. Disable if the account already has one."
  type        = bool
  default     = true
}

variable "existing_github_oidc_provider_arn" {
  description = "Existing token.actions.githubusercontent.com provider ARN when create_github_oidc_provider=false."
  type        = string
  default     = ""
}

variable "kubernetes_namespace" {
  type    = string
  default = "payments"
}
