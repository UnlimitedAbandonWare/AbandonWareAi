"""Portable bounded source-intent classifier; no files, network or prompt logs."""
import json
import re
import sys

CONTEXT = 'triggerReason=source-edit-intent; Before any application-source mutation, use $demo1-source-edit-three-way-preflight. Do not mutate source unless NEUTRAL_QUERY returns APPLY and the existing source-owner guard passes.'


def source_edit_intent(prompt):
    if not isinstance(prompt,str) or not prompt.strip():return False
    mutation=r'(?i)(\bimplement\b|\bmodify\b|\bpatch\b|\bfix\b|\bedit\b|\badd\b|\bremove\b|\brefactor\b|\bupdate\b|\bchange\b|구현|수정|패치|고쳐|변경|추가|제거|리팩터)'
    root=r'(?<![A-Za-z0-9_.\\/:-])(?:main[\\/]java[\\/]|main[\\/]resources[\\/]|app[\\/]src[\\/]main[\\/]java_clean[\\/]|app[\\/]src[\\/]main[\\/]resources[\\/])'
    source=r'(?i)(\bapplication[ -]?source\b|\bsource[ -]?code\b|\bbackend(?:[ -]?implementation)?\b|소스[ ]?코드|애플리케이션[ ]?소스)'
    read_only=r'(?i)(read[ -]?only|do not edit|do not modify|without editing|analysis only|analy[sz]e only|review only|audit only|plan only|수정\s?하지\s?마|편집\s?하지\s?마|분석\s?만|검토\s?만|계획\s?만|write[ -]?only)'
    sequence=r'(?is)\bthen\b.{0,120}\b(?:please\s+)?(?:implement|modify|patch|fix|edit|add|remove|refactor|update|change)\b.{0,160}'+root
    if not (re.search(mutation,prompt) and (re.search(root,prompt,re.I) or re.search(source,prompt))):return False
    restrictions=list(re.finditer(read_only,prompt))
    return not restrictions or any(m.start()>=restrictions[-1].end() for m in re.finditer(sequence,prompt))


def main():
    try:
        raw=sys.stdin.buffer.read(65537)
        if len(raw)>65536:return 0
        event=json.loads(raw.decode('utf-8-sig'))
        triggered=source_edit_intent(event.get('prompt'))
        if '--classify' in sys.argv:sys.stdout.write('true' if triggered else 'false')
        elif triggered:
            result=json.dumps({'hookSpecificOutput':{'hookEventName':'UserPromptSubmit','additionalContext':CONTEXT}},separators=(',',':'))
            if len(result.encode())<=1024:sys.stdout.write(result)
    except (ValueError,TypeError,AttributeError):pass
    return 0


if __name__=='__main__':raise SystemExit(main())
