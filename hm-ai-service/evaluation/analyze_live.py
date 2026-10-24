"""Summarize existing run artifacts without changing original requests/verdicts."""
import argparse
import json
import math
import re
from collections import defaultdict
from pathlib import Path


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def analyze(path):
    rows = [json.loads(line) for line in (path / 'turns.jsonl').read_text(encoding='utf-8-sig').splitlines()]
    valid = [r for r in rows if r['category'] != 'validation']
    faq = [r for r in rows if 'sources_any' in r['expected']]
    tools = [r for r in rows if r['expected'].get('tools_all')]
    outside = [r for r in rows if r['category'] == 'out_of_scope']
    groups = defaultdict(list)
    for r in rows:
        groups[r['case_id']].append(r)
    multi = [g for g in groups.values() if g[0]['category'] == 'multi_turn']
    good = lambda r: r['response']['body'].get('success') is True
    result = {
        'valid_answer_responses': [sum(good(r) for r in valid), len(valid)],
        'automatic_turn_passes': [sum(r['automatic_pass'] for r in rows), len(rows)],
        'automatic_scenario_passes': [sum(all(r['automatic_pass'] for r in g) for g in groups.values()), len(groups)],
        'faq_source_hits': [sum(r['checks'].get('source_hit', False) for r in faq), len(faq)],
        'required_tool_turn_passes': [sum(r['automatic_pass'] for r in tools), len(tools)],
        'complete_multi_turn_scenarios': [sum(all(r['automatic_pass'] for r in g) for g in multi), len(multi)],
        'out_of_scope_clean_sources': [sum(good(r) and r['checks'].get('sources_empty', False) for r in outside), len(outside)],
        'valid_latency_ms': {'p50': percentile([r['response']['elapsed_ms'] for r in valid], .5),
                             'p95': percentile([r['response']['elapsed_ms'] for r in valid], .95),
                             'max': max((r['response']['elapsed_ms'] for r in valid), default=None)},
        'http_504': sum(r['response']['http_status'] == 504 for r in rows),
        'failed_turns': [{'case_id':r['case_id'], 'turn':r['turn'], 'failed_checks':[k for k,v in r['checks'].items() if not v],
                         'error':r['response']['body'].get('errorMsg')} for r in rows if not r['automatic_pass']],
        'semantic_accuracy': None,
        'note': 'Frozen contract checks, not semantic accuracy; valid latency includes failed valid requests.'
    }
    tool_timings = defaultdict(list)
    for row in rows:
        for tool in row.get('tool_logs', []):
            duration = tool.get('duration')
            if isinstance(duration, (int, float)):
                tool_timings[tool['action']].append(duration)
    result['tool_latency_ms'] = {name: {'calls': len(values), 'p50': percentile(values, .5),
        'p95': percentile(values, .95)} for name, values in sorted(tool_timings.items())}
    result['tool_latency_note'] = 'Actual trace durations, including failures and nested calls; do not sum nested tool times with components.'
    logpath = path / 'diagnostics.log'
    if logpath.exists():
        lines = logpath.read_text(encoding='utf-8-sig', errors='replace').splitlines()
        result['partial_facts_fallbacks'] = sum('AI_FACTS_FALLBACK requestId=' in line for line in lines)
        request_ids = defaultdict(list)
        calls = defaultdict(list)
        components = defaultdict(list)
        citations = {}
        skills = {}
        for line in lines:
            match = re.search(r'AI_REQUEST_START requestId=([^,]+), conversationId=(\S+)', line)
            if match:
                request_ids[match[2]].append(match[1])
            match = re.search(r'AI_MODEL_CALL requestId=([^,]+), scene=([^,]+), round=(\d+), durationMs=(\d+), outcome=([^,]+), reserved=(\d+), inputTokens=(null|\d+), outputTokens=(null|\d+)', line)
            if match:
                calls[match[1]].append({'scene':match[2], 'round':int(match[3]), 'duration_ms':int(match[4]), 'outcome':match[5],
                    'reserved_estimate':int(match[6]), 'input_tokens':None if match[7]=='null' else int(match[7]),
                    'output_tokens':None if match[8]=='null' else int(match[8])})
                extra = re.search(r'timeoutMs=(\d+), finishReason=(\w+)', line)
                if extra:
                    calls[match[1]][-1].update(timeout_ms=int(extra[1]), finish_reason=extra[2])
            component = re.search(r'AI_COMPONENT requestId=([^,]+), component=([^,]+), durationMs=(\d+), outcome=([^,\s]+)', line)
            if component:
                components[component[1]].append({'component':component[2], 'duration_ms':int(component[3]), 'outcome':component[4]})
            citation = re.search(r'AI_CITATIONS requestId=([^,]+), status=([^,]+), retrieved=\[([^]]*)\], selected=\[([^]]*)\], accepted=\[([^]]*)\], rejected=\[([^]]*)\]', line)
            if citation:
                split_ids = lambda text: [part.strip() for part in text.split(',') if part.strip()]
                citations[citation[1]] = dict(status=citation[2], **{key:split_ids(citation[index])
                    for index,key in enumerate(['retrieved','selected','accepted','rejected'], 3)})
            skill = re.search(r'AI_SKILL_SELECTED requestId=([^,]+), name=([^,]+), version=([^,]+), sha256=([^,]+), reason=(\S+)', line)
            if skill:
                skills[skill[1]] = dict(name=skill[2], version=skill[3], sha256=skill[4], reason=skill[5])
        usage_rows = []
        for row in rows:
            conversation = row['request']['conversationId']
            ids = request_ids[conversation]
            if not ids:
                continue
            request_id = ids.pop(0)
            usage_rows.append({'case_id':row['case_id'], 'turn':row['turn'], 'request_id':request_id, 'calls':calls[request_id], 'citations':citations.get(request_id),
                               'skill':skills.get(request_id), 'expected_skill':row.get('expected_skill'), 'components':components[request_id]})
        flat = [c for r in usage_rows for c in r['calls']]
        component_rows = [c for r in usage_rows for c in r['components']]
        if component_rows:
            names = sorted(set(c['component'] for c in component_rows))
            result['component_latency_ms'] = {name: {'calls':sum(c['component']==name for c in component_rows),
                'p50':percentile([c['duration_ms'] for c in component_rows if c['component']==name], .5),
                'p95':percentile([c['duration_ms'] for c in component_rows if c['component']==name], .95)} for name in names}
        if skills:
            expectations = [r for r in usage_rows if r.get('expected_skill') is not None]
            result['skill_trace'] = {
                'traced_requests':len(skills),
                'selected_requests':sum(v['name']!='none' for v in skills.values()),
                'versions':sorted(set(v['name']+'@'+v['version'] for v in skills.values() if v['name']!='none')),
                'explicit_routing_expectations':[sum(r['skill'] is not None and r['skill']['name']==r['expected_skill'] for r in expectations),len(expectations)]}
        if citations:
            result['citation_trace'] = {
                'traced_answers':len(citations),
                'missing':sum(v['status']=='missing' for v in citations.values()),
                'malformed':sum(v['status'] in ['malformed','invalid_id_format'] for v in citations.values()),
                'answers_with_rejected_ids':sum(bool(v['rejected']) for v in citations.values())}
            result['finish_reasons'] = dict(__import__('collections').Counter(c.get('finish_reason','unknown') for c in flat))
        if flat:
            result['chat_call_usage'] = {'calls':len(flat), 'known_usage_calls':sum(c['input_tokens'] is not None and c['output_tokens'] is not None for c in flat),
                'known_input_tokens':sum(c['input_tokens'] or 0 for c in flat), 'known_output_tokens':sum(c['output_tokens'] or 0 for c in flat),
                'excludes':'Embedding calls; provider work with missing usage remains unknown, never counted as zero.'}
            (path/'model-usage.json').write_text(json.dumps(usage_rows,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (path/'analysis.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('runs', nargs='+', type=Path)
    args = parser.parse_args()
    for run in args.runs:
        print(run.name, json.dumps(analyze(run), ensure_ascii=False))
