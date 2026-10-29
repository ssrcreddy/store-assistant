resource "aws_ecs_cluster" "main" {
  name = var.project

  setting {
    name  = "containerInsights"
    value = "disabled" # about $3-5/month extra; turn on when you need per-task metrics
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.project}"
  retention_in_days = 7
}

locals {
  registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"

  log_config = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.app.name
      awslogs-region        = var.region
      awslogs-stream-prefix = "app"
    }
  }
}

# One task, two containers. They share the task's network namespace, so the orchestrator reaches
# the mock APIs on localhost. With real legacy APIs, drop the mock container and point
# MOCK_API_URL at them (PrivateLink or a private endpoint).
resource "aws_ecs_task_definition" "app" {
  family                   = var.project
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name         = "mock-apis"
      image        = "${aws_ecr_repository.app["mock-apis"].repository_url}:${var.image_tag}"
      essential    = true
      memory       = 256
      portMappings = [{ containerPort = 4000, protocol = "tcp" }]
      environment = [
        { name = "MOCK_LATENCY_MS", value = "60" },
        { name = "MOCK_FAILURE_RATE", value = "0" },
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -qO- http://localhost:4000/health || exit 1"]
        interval    = 10
        timeout     = 3
        retries     = 3
        startPeriod = 5
      }
      logConfiguration = local.log_config
    },
    {
      name         = "orchestrator"
      image        = "${aws_ecr_repository.app["orchestrator"].repository_url}:${var.image_tag}"
      essential    = true
      memory       = 768
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      dependsOn    = [{ containerName = "mock-apis", condition = "HEALTHY" }]
      environment = [
        { name = "LLM_PROVIDER", value = "bedrock" },
        { name = "AWS_REGION", value = var.region },
        { name = "FAST_MODEL", value = var.fast_model },
        { name = "SMART_MODEL", value = var.smart_model },
        { name = "LLM_BUDGET_USD", value = tostring(var.llm_budget_usd) },
        { name = "LLM_WARMUP", value = "true" },
        { name = "MOCK_API_URL", value = "http://localhost:4000" },
        # Never expose the raw LLM inputs on a public URL.
        { name = "DEBUG_ENDPOINTS", value = "false" },
      ]
      logConfiguration = local.log_config
    },
  ])
}

resource "aws_ecs_service" "app" {
  name            = var.project
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count

  capacity_provider_strategy {
    capacity_provider = var.use_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # no NAT gateway; the security group blocks all inbound except the ALB
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.orchestrator.arn
    container_name   = "orchestrator"
    container_port   = 8080
  }

  # The JVM starts slowly on 0.5 vCPU, and readiness also waits for the warm-up call.
  health_check_grace_period_seconds = 180

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_lb_listener_rule.from_cloudfront, aws_ecs_cluster_capacity_providers.main]
}
