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

data "aws_iam_policy_document" "bedrock" {
  statement {
    sid     = "InvokeClaudeMessagesApi"
    actions = ["bedrock-mantle:CreateInference"]
    # Narrow this to the two model ARNs once you've confirmed their exact ARN format in your
    # account (CloudTrail shows the resource on each call).
    resources = ["*"]
  }

  # llm_provider = "bedrock-runtime". Global inference profiles route a request to any region,
  # so the foundation-model ARNs use a region wildcard.
  statement {
    sid     = "InvokeClaudeRuntime"
    actions = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
    resources = [
      "arn:aws:bedrock:*:${data.aws_caller_identity.current.account_id}:inference-profile/*anthropic.claude-*",
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-*",
    ]
  }
}

resource "aws_iam_role_policy" "bedrock" {
  name   = "bedrock-claude"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.bedrock.json
}
