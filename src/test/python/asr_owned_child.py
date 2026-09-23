"""Synthetic process-lifecycle fixture; no model or microphone."""
import json, sys, time
print(json.dumps({'type':'ready', 'cpuThreads':int(sys.argv[2])}), flush=True)
for line in sys.stdin:
    event=json.loads(line)
    print('{"type":"busy"}', flush=True)
    time.sleep(60)
