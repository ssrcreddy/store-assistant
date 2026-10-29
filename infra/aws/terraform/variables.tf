variable "project" {
  description = "Name prefix for every resource."
  type        = string
  default     = "store-assistant"
}

variable "region" {
  description = "AWS region. Claude Haiku 5.5 and Sonnet 5.5 must be enabled in Bedrock here."
  type        = string
  default     = "us-east-1"
}

variable "alert_email" {
  description = "Where AWS Budgets sends cost alerts."
  type        = string
}

variable "monthly_budget_usd" {
  description = "AWS Budgets alert threshold for the whole account, in USD per month."
  type        = number
  default     = 40
}

variable "llm_budget_usd" {
  description = "The orchestrator's own LLM spend cap (LLM_BUDGET_USD). Kept low because the demo URL is public. In-memory, so it resets when the task restarts."
  type        = number
  default     = 10
}

variable "fast_model" {
  type    = string
  default = "anthropic.claude-haiku-5-5"
}

variable "smart_model" {
  type    = string
  default = "anthropic.claude-sonnet-5-5"
}

variable "image_tag" {
  description = "Tag of the orchestrator and mock-apis images in ECR. deploy.sh sets it to the git commit."
  type        = string
  default     = "latest"
}

variable "desired_count" {
  description = "Number of tasks. Keep at 1 (or 0 to pause compute): sessions, the PII vault and pending confirmations are in memory until Redis is added."
  type        = number
  default     = 1

  validation {
    condition     = var.desired_count <= 1
    error_message = "State is in memory, so more than one task would split sessions and confirmations. Add Redis first."
  }
}

variable "use_spot" {
  description = "Run on Fargate Spot (about 70% cheaper; AWS can stop the task with 2 minutes' notice, and ECS replaces it)."
  type        = bool
  default     = true
}
