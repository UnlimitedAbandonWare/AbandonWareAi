#!/usr/bin/env python3
import yaml, io, os, sys, pathlib

def merge_texts(paths, order):
    texts = []
    for key in order:
        if key == 'trait':
            for p in paths.get('traits', []):
                with open(p, 'r', encoding='utf-8') as f:
                    texts.append(f.read().strip())
        elif key == 'system':
            with open(paths['system'], 'r', encoding='utf-8') as f:
                texts.append(f.read().strip())
    return "\n\n---\n".join(texts) + "\n"

def main(manifest_path, agent_id):
    with open(manifest_path, 'r', encoding='utf-8') as f:
        data = yaml.safe_load(f)
    agent = None
    for a in data.get('agents', []):
        if a.get('id') == agent_id:
            agent = a
            break
    if not agent:
        print(f"Agent {agent_id} not found", file=sys.stderr)
        sys.exit(2)
    order = agent.get('merge', {}).get('order', ['trait','system'])
    out_path = agent.get('output', {}).get('path', 'agent_scaffold/out/merged.prompt')
    text = merge_texts({"system":agent['system'], "traits":agent.get('traits',[])}, order)
    pathlib.Path(os.path.dirname(out_path)).mkdir(parents=True, exist_ok=True)
    with open(out_path, 'w', encoding=agent.get('output',{}).get('encoding','utf-8')) as f:
        f.write(text)
    print(f"Written: {out_path}")

if __name__ == "__main__":
    manifest = 'agent_scaffold/prompts.manifest.yaml'
    agent_id = 'gpt_pro_src111'
    if len(sys.argv) > 1:
        manifest = sys.argv[1]
    if len(sys.argv) > 2:
        agent_id = sys.argv[2]
    main(manifest, agent_id)
