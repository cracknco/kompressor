#!/usr/bin/env bash
# check-devicefarm-budget.sh
#
# Reads month-to-date AWS Device Farm spend via Cost Explorer and compares
# against MOBILE_CI_BUDGET_MONTH (USD, integer). Intended to run before
# the iOS device smoke job in CI.
#
# Exit codes:
#   0  — within budget, proceed
#   78 — over budget, caller should skip (conventional "EX_CONFIG" skip code)
#   1  — unexpected error
#
# Env:
#   MOBILE_CI_BUDGET_MONTH   required, integer USD (e.g. 75)
#   AWS_REGION               optional, default us-east-1 (Cost Explorer is global
#                            but CLI requires a region)
#
# Requires: aws CLI v2, jq, bc.

set -euo pipefail

: "${MOBILE_CI_BUDGET_MONTH:?MOBILE_CI_BUDGET_MONTH not set}"

budget="${MOBILE_CI_BUDGET_MONTH}"
region="${AWS_REGION:-us-east-1}"

start="$(date -u +%Y-%m-01)"
end="$(date -u +%Y-%m-%d)"
if [[ "${start}" == "${end}" ]]; then
  # First day of month: nothing billed yet, short-circuit.
  echo "budget-guard: first day of month, skipping Cost Explorer call (spent=0 USD, budget=${budget} USD)"
  exit 0
fi

if ! spend_raw=$(aws ce get-cost-and-usage \
  --region "${region}" \
  --time-period "Start=${start},End=${end}" \
  --granularity MONTHLY \
  --metrics UnblendedCost \
  --filter '{"Dimensions":{"Key":"SERVICE","Values":["AWS Device Farm"]}}' \
  --query 'ResultsByTime[0].Total.UnblendedCost.Amount' \
  --output text 2>&1); then
  echo "::warning::Cost Explorer unavailable (${spend_raw}). Proceeding without budget check."
  exit 0
fi

if [[ -z "${spend_raw}" || "${spend_raw}" == "None" ]]; then
  spend_raw="0"
fi

spent_int=$(printf "%.0f" "${spend_raw}")

echo "budget-guard: Device Farm spend month-to-date = \$${spend_raw} (rounded \$${spent_int}) / budget \$${budget}"

if (( spent_int >= budget )); then
  echo "::warning::Device Farm month-to-date spend (\$${spent_int}) has reached the budget cap (\$${budget}). Skipping device smoke run."
  exit 78
fi

exit 0
