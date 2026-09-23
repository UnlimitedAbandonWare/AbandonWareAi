#!/usr/bin/env python3
import argparse, yaml, os
def read(p):
    with open(p, 'r', encoding='utf-8') as f:
        return f.read()
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--agent', required=True)
    args = ap.parse_args()
    m = yaml.safe_load(read(args.manifest))
    for a in m['agents']:
        if a['id'] != args.agent:
            continue
        parts = []
        for t in a.get('merge',{}).get('order',['trait','system']):
            if t=='trait':
                for tp in a.get('traits', []):
                    parts.append(read(os.path.join(os.path.dirname(args.manifest), tp) if not os.path.isabs(tp) else tp))
            elif t=='system':
                sp = a['system']
                parts.append(read(os.path.join(os.path.dirname(args.manifest), sp) if not os.path.isabs(sp) else sp))
        outp = a['output']['path']
        outp = os.path.join(os.path.dirname(args.manifest), outp) if not os.path.isabs(outp) else outp
        os.makedirs(os.path.dirname(outp), exist_ok=True)
        with open(outp,'w',encoding=a['output'].get('encoding','utf-8')) as f:
            f.write("\n\n".join(parts))
        print("Wrote", outp)
        return
    raise SystemExit("Agent not found")
if __name__ == "__main__":
    main()
