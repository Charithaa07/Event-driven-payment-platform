output "cluster_name" {
  value = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}

output "ecr_repository_urls" {
  value = { for name, repository in aws_ecr_repository.service : name => repository.repository_url }
}

output "rds_endpoints" {
  value = { for name, database in aws_db_instance.service : name => database.address }
}

output "rds_master_secret_arns" {
  description = "RDS-managed Secrets Manager credentials consumed only at deployment time."
  value       = { for name, database in aws_db_instance.service : name => database.master_user_secret[0].secret_arn }
}

output "redis_endpoint" {
  value = {
    address = aws_elasticache_serverless_cache.redis.endpoint[0].address
    port    = aws_elasticache_serverless_cache.redis.endpoint[0].port
  }
}

output "msk_cluster_arn" {
  value = aws_msk_serverless_cluster.main.arn
}

output "pod_identity_role_arns" {
  value = { for name, role in aws_iam_role.workload : name => role.arn }
}
