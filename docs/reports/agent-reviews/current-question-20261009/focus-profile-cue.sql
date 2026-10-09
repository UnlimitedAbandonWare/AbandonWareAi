-- One known failing live profile only. Preserve FIXED, API_ONLY and all non-model preferences.
UPDATE nova_focus_profile
SET settings_json = REPLACE(settings_json,
    '"modelId":"llmrouter.openai-economy"',
    '"modelId":"llmrouter.gemini-cue"'),
    settings_version = settings_version + 1
WHERE channel = 'live'
  AND id LIKE 'dfa4c4c2aa24%'
  AND LOWER(LEFT(RAWTOHEX(HASH('SHA-256', STRINGTOUTF8(owner_key))),12)) = '33d5f21b86f3'
  AND settings_version = 6
  AND settings_json LIKE '%"mode":"FIXED","modelId":"llmrouter.openai-economy"%';
