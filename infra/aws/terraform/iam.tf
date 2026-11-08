data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Used by ECS itself: pull images from ECR and write logs.
resource "aws_iam_role" "execution" {
  name               = "${var.project}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Used by the application: call Claude in Amazon Bedrock. No API key exists anywhere; the SDK
# signs requests with this role's temporary credentials.
resource "aws_iam_role" "task" {
  name               = "${var.project}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

locals {
  llm_models = distinct([var.fast_model, var.smart_model])
  # "global.anthropic.claude-sonnet-4-6" -> "anthropic.claude-sonnet-4-6"
  llm_base_models = distinct([for m in local.llm_models : replace(m, "/^(global|us|eu|apac|jp|au)\\./", "")])
}

# The task may call only the endpoint in use (llm_provider) and only the two configured models.
data "aws_iam_policy_document" "bedrock" {
  # Messages API endpoint. Its calls didn't appear in CloudTrail while the account was blocked, so
  # the resource ARN format is unconfirmed; narrow this once a successful call shows it.
  dynamic "statement" {
    for_each = var.llm_provider == "bedrock" ? [1] : []
    content {
      sid       = "InvokeClaudeMessagesApi"
      actions   = ["bedrock-mantle:CreateInference"]
      resources = ["*"]
    }
  }

  # InvokeModel endpoint. CloudTrail shows each call authorized against both the inference profile
  # and the foundation model. Global profiles route to any region, hence the region wildcard on the
  # foundation-model ARNs.
  dynamic "statement" {
    for_each = var.llm_provider == "bedrock-runtime" ? [1] : []
    content {
      sid     = "InvokeClaudeRuntime"
      actions = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      resources = concat(
        [for m in local.llm_models : "arn:aws:bedrock:*:${data.aws_caller_identity.current.account_id}:inference-profile/${m}"],
        [for m in local.llm_base_models : "arn:aws:bedrock:*::foundation-model/${m}"],
      )
    }
  }
}

resource "aws_iam_role_policy" "bedrock" {
  name   = "bedrock-claude"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.bedrock.json
}
