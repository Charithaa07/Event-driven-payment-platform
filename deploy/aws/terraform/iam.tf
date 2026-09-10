locals {
  workload_roles = {
    payment = {
      service_account = "payment-platform-payment"
      topics          = ["payments.created.v1"]
      groups          = []
    }
    transaction = {
      service_account = "payment-platform-transaction"
      topics          = ["payments.created.v1", "payments.created.v1.DLT"]
      groups          = ["transaction-service*"]
    }
    audit = {
      service_account = "payment-platform-audit"
      topics          = ["payments.created.v1", "payments.created.v1.audit.DLT"]
      groups          = ["audit-service*"]
    }
    notification = {
      service_account = "payment-platform-notification"
      topics          = ["payments.created.v1", "payments.created.v1.notification.DLT"]
      groups          = ["notification-service*"]
    }
  }

  msk_cluster_arn = "arn:${data.aws_partition.current.partition}:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:cluster/${aws_msk_serverless_cluster.main.cluster_name}/*"

  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : var.existing_github_oidc_provider_arn
}

data "aws_iam_policy_document" "pod_identity_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "workload" {
  for_each = local.workload_roles

  name               = "${local.name}-${each.key}-pod"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume.json
}

data "aws_iam_policy_document" "workload_msk" {
  for_each = local.workload_roles

  statement {
    sid       = "ClusterConnection"
    effect    = "Allow"
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [local.msk_cluster_arn]
  }

  statement {
    sid    = "TopicAccess"
    effect = "Allow"
    actions = [
      "kafka-cluster:CreateTopic",
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:ReadData",
      "kafka-cluster:WriteData"
    ]
    resources = [
      for topic in each.value.topics :
      "arn:${data.aws_partition.current.partition}:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:topic/${aws_msk_serverless_cluster.main.cluster_name}/*/${topic}"
    ]
  }

  dynamic "statement" {
    for_each = length(each.value.groups) > 0 ? [1] : []
    content {
      sid     = "ConsumerGroupAccess"
      effect  = "Allow"
      actions = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
      resources = [
        for group in each.value.groups :
        "arn:${data.aws_partition.current.partition}:kafka:${var.aws_region}:${data.aws_caller_identity.current.account_id}:group/${aws_msk_serverless_cluster.main.cluster_name}/*/${group}"
      ]
    }
  }
}

resource "aws_iam_role_policy" "workload_msk" {
  for_each = local.workload_roles

  name   = "msk-access"
  role   = aws_iam_role.workload[each.key].id
  policy = data.aws_iam_policy_document.workload_msk[each.key].json
}

resource "aws_eks_pod_identity_association" "workload" {
  for_each = local.workload_roles

  cluster_name    = aws_eks_cluster.main.name
  namespace       = var.kubernetes_namespace
  service_account = each.value.service_account
  role_arn        = aws_iam_role.workload[each.key].arn

  depends_on = [aws_eks_addon.core]
}

data "tls_certificate" "github" {
  count = var.create_github_oidc_provider ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github[0].certificates[0].sha1_fingerprint]
}

data "aws_iam_policy_document" "github_deploy_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.github_environment}"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${local.name}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_deploy_assume.json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "EcrLogin"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart"
    ]
    resources = [for repository in aws_ecr_repository.service : repository.arn]
  }

  statement {
    sid       = "EksDescribe"
    effect    = "Allow"
    actions   = ["eks:DescribeCluster"]
    resources = [aws_eks_cluster.main.arn]
  }

  statement {
    sid    = "DiscoverManagedEndpoints"
    effect = "Allow"
    actions = [
      "rds:DescribeDBInstances",
      "kafka:ListClustersV2",
      "kafka:GetBootstrapBrokers",
      "elasticache:DescribeServerlessCaches"
    ]
    resources = ["*"]
  }

  statement {
    sid     = "ReadDatabaseCredentials"
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      for database in aws_db_instance.service : database.master_user_secret[0].secret_arn
    ]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy-platform"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}

resource "aws_eks_access_entry" "github_deploy" {
  cluster_name  = aws_eks_cluster.main.name
  principal_arn = aws_iam_role.github_deploy.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "github_deploy" {
  cluster_name  = aws_eks_cluster.main.name
  principal_arn = aws_iam_role.github_deploy.arn
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSAdminPolicy"

  access_scope {
    type       = "namespace"
    namespaces = [var.kubernetes_namespace]
  }

  depends_on = [aws_eks_access_entry.github_deploy]
}
