# apikit.ps1 — lite API test kit wrapper (DEMO1-DEVIN-LITE-API-TESTKIT-20260929).
# Refreshes User-scope env vars into this Process before invoking Python so a
# stale inherited env (e.g. the rotated Jev key case) does not silently win.
# Never prints key values. Exit codes pass through: 0 ok / 3 failed / 2 error.
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $Args)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot   # scripts/ -> src/

# Refresh User-scope variables into Process scope (key values never echo).
foreach ($name in @(
    'AI_GATEWAY_API_KEY','OPENAI_API_KEY','GEMINI_API_KEY','GOOGLE_API_KEY',
    'GROQ_API_KEY','ZAI_API_KEY','OPENCODE_API_KEY','OPENCODE_GO_API_KEY',
    'ANTHROPIC_API_KEY','MISTRAL_API_KEY','OPENROUTER_API_KEY',
    'BRAVE_API_KEY_FREE','BRAVE_API_KEY','TAVILY_API_KEY','SERPAPI_API_KEY',
    'NAVER_CLIENT_ID','NAVER_CLIENT_SECRET','NAVER_KEYS','SONIOX_API_KEY',
    'CEREBRAS_API_KEY','KAKAO_REST_KEY','KAKAO_REST_API_KEY',
    'DEEPGRAM_API_KEY','DEEPGRAM_API_KEY_SECONDARY','PINECONE_API_KEY',
    'UPSTASH_VECTOR_API_KEY','UPSTASH_VECTOR_URL','UPSTASH_VECTOR_TOKEN',
    'UPSTASH_REDIS_REST_URL','UPSTASH_REDIS_REST_TOKEN',
    'AWX_AGENT_SPEND_GUARD','AWX_AGENT_ALLOW_PAID_MODELS',
    'AWX_APIKIT_OPENAI_MODEL','OLLAMA_HOST','LLM_BASE_URL')) {
    $userVal = [Environment]::GetEnvironmentVariable($name, 'User')
    if ($null -ne $userVal -and $userVal -ne '') {
        [Environment]::SetEnvironmentVariable($name, $userVal, 'Process')
    }
}

Push-Location $root
try {
    python -B scripts/apikit @Args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
