SSOT: var/codex-assist-chat-oauth-model-order-20261006/README.md
python -B scripts/chat_oauth_model_order_assist.py pin --root .
python -B scripts/chat_oauth_model_order_assist.py cover --root .
python -B scripts/chat_oauth_model_order_assist.py diff-forbid --root . --diff <owned.diff>
node --test scripts/chat_oauth_model_order_gap_tests.cjs
Product source stays with Codex journal chat-oauth-model-order-20261006-6fa035b9. Scan exit 0 is not a product PASS.
