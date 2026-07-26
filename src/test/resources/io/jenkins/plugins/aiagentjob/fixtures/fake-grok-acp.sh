#!/bin/sh
set -eu

test -n "${XAI_API_KEY:-}"
printf '%s\n' "$*" > acp-command.txt

IFS= read -r initialize_request
initialize_id=$(printf '%s\n' "$initialize_request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"xai.api_key","name":"API key"},{"id":"cached_token","name":"Cached token"}]}}\n' "$initialize_id"

IFS= read -r authenticate_request
authenticate_id=$(printf '%s\n' "$authenticate_request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
case "$authenticate_request" in
  *'"method":"authenticate"'*'"methodId":"xai.api_key"'*'"headless":true'*) ;;
  *) exit 7 ;;
esac
printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$authenticate_id"

IFS= read -r session_request
session_id=$(printf '%s\n' "$session_request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"session-1","configOptions":[]}}\n' "$session_id"

IFS= read -r prompt_request
prompt_id=$(printf '%s\n' "$prompt_request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
case "$prompt_request" in
  *'"method":"session/prompt"'*) ;;
  *) exit 8 ;;
esac

printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"tool_call","toolCallId":"call-1","title":"touch approved.txt","kind":"execute","status":"pending","rawInput":{"command":"touch approved.txt"}}}}'
permission_input=${GROK_FIXTURE_PERMISSION_INPUT:-$XAI_API_KEY}
printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":\"permission-1\",\"method\":\"session/request_permission\",\"params\":{\"sessionId\":\"session-1\",\"toolCall\":{\"toolCallId\":\"call-1\",\"title\":\"touch approved.txt\",\"kind\":\"execute\",\"status\":\"pending\",\"rawInput\":{\"command\":\"$permission_input\"}},\"options\":[{\"optionId\":\"allow-once\",\"name\":\"Allow once\",\"kind\":\"allow_once\"},{\"optionId\":\"reject-once\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]}}"

IFS= read -r approval_response
printf '%s\n' "$approval_response" > approval-response.json
case "$approval_response" in
  *'"outcome":"selected"'*'"optionId":"allow-once"'*) ;;
  *) exit 9 ;;
esac

touch approved.txt
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"tool_call_update","toolCallId":"call-1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"created approved.txt"}}],"rawOutput":{"output_for_prompt":"created approved.txt","exit_code":0}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Created approved.txt"}}}}'
printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn","_meta":{"modelId":"grok-4.5","usage":{"inputTokens":1200,"outputTokens":80,"totalTokens":1280,"cachedReadTokens":900,"reasoningTokens":20,"modelCalls":1,"apiDurationMs":500,"costUsdTicks":25000000,"modelUsage":{"grok-4.5-build":{"modelCalls":1}},"numTurns":1}}}}\n' "$prompt_id"
