"""Run real customer-service requests against local Docker (stdlib only).

No model mocking, answer rewriting, automatic retries, or semantic grading.
One fresh synthetic account per scenario avoids cross-conversation long memory.
"""
import argparse
import hashlib
import json
import math
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / 'hm-ai-service/src/test/resources/evaluation/customer-service-cases.json'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward a login token to an unexpected destination.


HTTP = urllib.request.build_opener(NoRedirect)


def request(base, path, token=None, body=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = token
    req = urllib.request.Request(base + path, headers=headers, method=method,
                                 data=None if body is None else json.dumps(body).encode())
    started = time.perf_counter()
    try:
        with HTTP.open(req, timeout=100) as response:
            status, raw = response.status, response.read().decode('utf-8')
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read().decode('utf-8', errors='replace')
    except (urllib.error.URLError, TimeoutError) as error:
        return {'http_status': None, 'elapsed_ms': round((time.perf_counter()-started)*1000, 2),
                'body': {}, 'transport_error': str(error)}
    try:
        data = json.loads(raw)
    except ValueError:
        data = {'non_json_body': raw[:2000]}
    return {'http_status': status, 'elapsed_ms': round((time.perf_counter()-started)*1000, 2),
            'body': data}


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, encoding='utf-8', errors='replace',
                                   stderr=subprocess.PIPE, timeout=30).strip()


def trace_request_id(response):
    data = response.get('body', {}).get('data')
    task = data.get('task') if isinstance(data, dict) else None
    value = task.get('requestId') if isinstance(task, dict) else None
    return value if isinstance(value, str) and re.fullmatch(
        r'[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}', value) else None


def login(base, phone):
    response = request(base, '/user/code?phone='+phone, method='POST')
    if response['body'].get('success') is not True:
        raise RuntimeError('Test account code request failed: '+str(response))
    code = command('docker', 'exec', 'hmdp-redis', 'redis-cli', '--raw', 'GET', 'login:code:'+phone)
    response = request(base, '/user/login', body={'phone': phone, 'code': code})
    if response['body'].get('success') is not True:
        raise RuntimeError('Test account login failed (response omitted to protect credentials)')
    token = response['body']['data']
    user = request(base, '/user/me', token)['body']
    if not user.get('success'):
        raise RuntimeError('Test account identity check failed')
    return token, user['data']['id']


def replace(value, shop_id, shop_name):
    if isinstance(value, str):
        if value == '{{shop_id}}':
            return shop_id
        return value.replace('{{shop_id}}', str(shop_id)).replace('{{shop_name}}', shop_name)
    if isinstance(value, list):
        return [replace(v, shop_id, shop_name) for v in value]
    if isinstance(value, dict):
        return {k: replace(v, shop_id, shop_name) for k, v in value.items()}
    return value


def candidate_options(answer, shop_ids):
    # 候选必须是店铺ID；测试券与店铺恰好同号时也不能产生假通过。
    shop_text = re.sub(r'(?:优惠券|代金券|秒杀券|券)\s*ID\s*[：:=]\s*\d+', '', answer, flags=re.I)
    return all(re.search(r'ID\s*[：:=]\s*'+str(shop_id)+r'(?!\d)', shop_text, re.I) for shop_id in shop_ids)


def checks(expect, response, logs):
    body = response.get('body') or {}
    data = body.get('data') or {}
    result = {}
    if 'success' in expect:
        result['business_success'] = body.get('success') is expect['success']
    if 'http_status_any' in expect:
        result['http_status'] = response['http_status'] in expect['http_status_any']
    if 'sources_any' in expect:
        result['source_hit'] = bool(set(data.get('sources', [])) & set(expect['sources_any']))
    if expect.get('sources_empty'):
        result['sources_empty'] = not data.get('sources')
    successful = [log for log in logs if log.get('status') == 'SUCCESS']
    for name in expect.get('tools_all', []):
        result['tool:'+name] = any(log.get('action') == name for log in successful)
    for name in expect.get('tools_forbidden', []):
        result['no_tool:'+name] = not any(log.get('action') == name for log in logs)
    for name, expected_args in expect.get('tool_args', {}).items():
        args = []
        for log in successful:
            if log.get('action') == name:
                try:
                    args.append(json.loads(log.get('actionInput') or '{}'))
                except ValueError:
                    pass
        result['args:'+name] = any(all(a.get(k) == v for k, v in expected_args.items()) for a in args)
    if 'answer_forbidden' in expect:
        result['forbidden_text_absent'] = not any(s in data.get('answer', '') for s in expect['answer_forbidden'])
    if 'answer_all' in expect:
        result['required_text_present'] = all(s in data.get('answer', '') for s in expect['answer_all'])
    if 'resolved_voucher_shop' in expect:
        target = expect['resolved_voucher_shop']
        matched = False
        for log in successful:
            if log.get('action') != 'query_shop_vouchers':
                continue
            try:
                arguments = json.loads(log.get('actionInput') or '{}')
            except ValueError:
                arguments = {}
            matched |= arguments.get('shopId') == target or bool(re.search(
                r'^店铺：[^\n]*（ID：' + re.escape(str(target)) + r'）\n', log.get('observation') or ''))
        result['resolved_voucher_shop'] = matched
    if 'error_any' in expect:
        result['expected_error'] = any(s in (body.get('errorMsg') or '') for s in expect['error_any'])
    # v2只核验明确可判定的业务结果，不要求某种工具序列或固定澄清措辞。
    answer = data.get('answer', '')
    if expect.get('no_voucher_data'):
        result['no_voucher_data'] = '优惠券ID：' not in answer and not re.search(r'库存[：:]\s*\d', answer)
    if 'clarification_shop_ids' in expect:
        result['clarification_options'] = candidate_options(answer, expect['clarification_shop_ids'])
    if 'answer_any' in expect:
        result['answer_alternative'] = any(value in answer for value in expect['answer_any'])
    if 'voucher_facts' in expect:
        lines = {int(m[1]): line for line in answer.splitlines()
                 if (m := re.match(r'^优惠券ID：(\d+)，', line))}
        for voucher_id, facts in expect['voucher_facts'].items():
            result['voucher_fact:'+str(voucher_id)] = all(value in lines.get(int(voucher_id), '') for value in facts)
    if expect.get('faq_evidence_exact'):
        documents = json.loads((ROOT/'hm-ai-service/src/main/resources/knowledge/customer-service-v1.json').read_text(encoding='utf-8'))['documents']
        by_source = {d['source']: d['content'] for d in documents}
        chosen = data.get('sources') or []
        paragraphs = [line for line in answer.splitlines() if line.startswith('平台规则')]
        expected_content = [by_source[s] for s in chosen if s in by_source]
        actual_content = [line.partition('：')[2] for line in paragraphs]
        result['faq_evidence_exact'] = bool(chosen) and len(expected_content) == len(chosen) and Counter(actual_content) == Counter(expected_content)
        if expect.get('faq_only'):
            result['faq_only'] = not '\n'.join(line for line in answer.splitlines() if line not in paragraphs).strip()
    if 'selection_expected' in expect:
        decision = data.get('selection')
        expected = expect['selection_expected']
        result['selection_present'] = isinstance(decision, dict)
        if isinstance(decision, dict):
            if 'candidate_ids' in expected:
                result['candidate_choice'] = decision.get('candidateVoucherId') in expected['candidate_ids']
            actual = {str(c.get('voucherId')): c for c in decision.get('checks', [])}
            for voucher_id, verdict in expected.get('verdicts', {}).items():
                result['condition:'+voucher_id] = actual.get(voucher_id, {}).get('verdict') in verdict
            if 'covers' in expected:
                result['comparison_coverage'] = set(actual) == set(map(str, expected['covers']))
    if 'selection_all_verdicts' in expect:
        selection = data.get('selection') or {}
        actual_checks = selection.get('checks') or []
        result['all_selection_verdicts'] = bool(actual_checks) and all(c.get('verdict') == expect['selection_all_verdicts'] for c in actual_checks)
    for tool, terms in expect.get('tool_observation_contains', {}).items():
        observations = '\n'.join(log.get('observation') or '' for log in successful if log.get('action') == tool)
        result['observation:'+tool] = all(term in observations for term in terms)
    # 任务完成与约束独立于自然语言措辞核验；缺少任务字段必须失败。
    task = data.get('task') or {}
    if 'task_status' in expect:
        wanted = expect['task_status']
        result['task_status'] = task.get('status') in (wanted if isinstance(wanted, list) else [wanted])
    if 'task_constraints' in expect:
        actual = task.get('constraints') or {}
        for key, value in expect['task_constraints'].items():
            result['constraint:'+key] = key in actual and actual[key] == value
    if 'task_steps' in expect:
        actual = {step.get('capability'): step for step in task.get('steps', [])}
        for capability, wanted in expect['task_steps'].items():
            result['step:'+capability] = actual.get(capability, {}).get('status') in (wanted if isinstance(wanted, list) else [wanted])
    for name, count in expect.get('tool_call_counts', {}).items():
        result['call_count:'+name] = sum(log.get('action') == name for log in logs) == count
    if 'model_calls_max' in expect:
        result['model_calls_bound'] = isinstance(task.get('modelCalls'), int) and 0 <= task['modelCalls'] <= expect['model_calls_max']
    if 'faq_answers' in expect:
        answers = data.get('faqAnswers')
        wanted = expect['faq_answers']
        result['faq_answer_count'] = isinstance(answers, list) and len(answers) == len(wanted)
        answers = answers if isinstance(answers, list) else []
        result['faq_question_indexes'] = [a.get('questionIndex') for a in answers] == list(range(len(answers)))
        for index, item in enumerate(wanted):
            matches = [a for a in answers if item['question_contains'] in a.get('question', '')]
            result['faq_question:'+str(index)] = len(matches) == 1
            if len(matches) == 1:
                actual = matches[0]
                if 'status' in item:
                    result['faq_status:'+str(index)] = actual.get('status') == item['status']
                if item.get('sources_empty'):
                    result['faq_sources_empty:'+str(index)] = not actual.get('sourceIds')
                if 'source_ids_any' in item:
                    result['faq_source_hit:'+str(index)] = bool(set(actual.get('sourceIds') or []) & set(item['source_ids_any']))
    return result


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values)*p)-1)] if values else None


def summarize(rows):
    latencies = [r['response']['elapsed_ms'] for r in rows if r['response']['body'].get('success') is True]
    cases = {}
    for r in rows:
        cases.setdefault(r['case_id'], []).append(r['automatic_pass'])
    return {'completed_turns': len(rows), 'completed_scenarios': len(cases),
            'successful_business_responses': sum(r['response']['body'].get('success') is True for r in rows),
            'automatic_turn_passes': sum(r['automatic_pass'] for r in rows),
            'automatic_scenario_passes': sum(all(v) for v in cases.values()),
            'successful_response_latency_ms': {'p50_nearest_rank': percentile(latencies, .5),
                                               'p95_nearest_rank': percentile(latencies, .95)},
            'failed_checks': dict(Counter(k for r in rows for k,v in r['checks'].items() if not v)),
            'semantic_accuracy': None, 'token_usage_per_turn': None,
            'note': 'Automatic checks are NOT answer accuracy. Review answers against rubric and tool observations separately.'}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, default=DATASET, help='Separate regression dataset; original stays frozen')
    parser.add_argument('--base-url', default='http://127.0.0.1:8080/api')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--phone-start', type=int, default=19910162000)
    parser.add_argument('--shop-id', type=int, default=1)
    parser.add_argument('--cases', help='Comma-separated case IDs; omit to run all')
    parser.add_argument('--gap', type=float, default=6.2, help='Minimum seconds between request starts')
    parser.add_argument('--self-check', action='store_true')
    args = parser.parse_args()
    if args.self_check:
        r = {'http_status': 200, 'body': {'success': True, 'data': {'sources': ['faq']}}}
        assert checks({'success': True, 'sources_any': ['faq']}, r, []) == {'business_success': True, 'source_hit': True}
        assert checks({'tools_all': ['t']}, r, [{'action': 't', 'status': 'FAILED'}]) == {'tool:t': False}
        assert checks({'tool_args': {'t': {'shopId': 1}}}, r, [{'action': 't', 'status': 'SUCCESS', 'actionInput': '{"shopId":2}'}]) == {'args:t': False}
        assert replace('{{shop_id}}', 1, 'shop') == 1
        assert percentile(list(range(1, 44)), .95) == 41
        assert checks({'tools_forbidden': ['t']}, r, []) == {'no_tool:t': True}
        assert checks({'tools_forbidden': ['t']}, r, [{'action':'t','status':'FAILED'}]) == {'no_tool:t': False}
        assert checks({'answer_all': ['事实']}, {'body': {'data': {'answer': '事实'}}}, []) == {'required_text_present': True}
        assert checks({'resolved_voucher_shop': 2}, r, [{'action': 'query_shop_vouchers', 'status': 'SUCCESS', 'actionInput': '{"shopName":"店"}', 'observation': '店铺：店（ID：2）\n库存未知'}]) == {'resolved_voucher_shop': True}
        assert checks({'resolved_voucher_shop': 1}, r, [{'action': 'query_shop_vouchers', 'status': 'SUCCESS', 'actionInput': '{"shopId":2}'}]) == {'resolved_voucher_shop': False}
        assert checks({'clarification_shop_ids': [16, 17]}, {'body':{'data':{'answer':'请选择ID：16 或ID：17'}}}, []) == {'clarification_options': True}
        assert checks({'no_voucher_data': True}, {'body':{'data':{'answer':'优惠券ID：15，库存：3'}}}, []) == {'no_voucher_data': False}
        assert checks({'voucher_facts': {'15':['库存：未知']}}, {'body':{'data':{'answer':'优惠券ID：15，库存：未知'}}}, []) == {'voucher_fact:15': True}
        assert not candidate_options('优惠券ID：16，库存：0\n优惠券ID：17，库存：3', [16,17])
        assert not candidate_options('店铺ID：160\n店铺ID：17', [16,17])
        assert candidate_options('请选择（ID:16）或（ID:17）', [16,17])
        sample = {'body':{'data':{'selection':{'candidateVoucherId':22,'checks':[{'voucherId':22,'verdict':'UNKNOWN'}]}}}}
        assert all(checks({'selection_expected':{'candidate_ids':[22],'verdicts':{'22':['UNKNOWN']},'covers':[22]}}, sample, []).values())
        assert not checks({'selection_expected':{'candidate_ids':[23]}}, sample, [])['candidate_choice']
        assert not checks({'selection_expected':{}}, r, [])['selection_present']
        task_response = {'body': {'data': {'task': {'status': 'PARTIAL', 'constraints': {'weekday': 3}, 'steps': [{'capability': 'FAQ', 'status': 'COMPLETED'}], 'modelCalls': 2}}}}
        assert checks({'task_status': 'COMPLETED'}, task_response, []) == {'task_status': False}
        assert checks({'task_constraints': {'weekday': 3, 'buyBudgetFen': None}}, task_response, []) == {'constraint:weekday': True, 'constraint:buyBudgetFen': False}
        assert checks({'task_steps': {'FAQ': 'COMPLETED', 'SHOP': 'COMPLETED'}}, task_response, []) == {'step:FAQ': True, 'step:SHOP': False}
        assert checks({'model_calls_max': 1}, task_response, []) == {'model_calls_bound': False}
        assert checks({'selection_all_verdicts': 'UNKNOWN'}, {'body': {'data': {'selection': {'checks': []}}}}, []) == {'all_selection_verdicts': False}
        assert checks({'selection_all_verdicts': 'UNKNOWN'}, {'body': {'data': {'selection': {'checks': [{'verdict': 'MATCH'}]}}}}, []) == {'all_selection_verdicts': False}
        assert checks({'tool_observation_contains': {'t': ['WEEKDAY_POLICY_UNCONFIRMED']}}, r, []) == {'observation:t': False}
        assert checks({'tool_call_counts': {'t': 1}}, r, [{'action': 't', 'status': 'SUCCESS'}]) == {'call_count:t': True}
        assert checks({'tool_call_counts': {'t': 1}}, r, [{'action': 't'}, {'action': 't'}]) == {'call_count:t': False}
        assert checks({'tool_call_counts': {'t': 0}}, r, [{'action': 't', 'status': 'FAILED'}]) == {'call_count:t': False}
        rid = str(uuid.uuid4())
        assert trace_request_id({'body': {'data': {'task': {'requestId': rid}}}}) == rid
        assert trace_request_id({'body': {'data': None}}) is None
        assert trace_request_id({'body': {'data': {'task': {'requestId': '../other'}}}}) is None
        assert trace_request_id({'body': {'data': []}}) is None
        faq_expected = {'faq_answers': [{'question_contains': 'refund', 'status': 'UNKNOWN', 'sources_empty': True}]}
        faq_response = {'body': {'data': {'faqAnswers': [{'questionIndex': 0, 'question': 'refund timing', 'status': 'UNKNOWN', 'sourceIds': []}]}}}
        assert all(checks(faq_expected, faq_response, []).values())
        assert not all(checks(faq_expected, r, []).values())
        assert not all(checks({'faq_answers': []}, faq_response, []).values())
        faq_response['body']['data']['faqAnswers'][0]['sourceIds'] = ['unrelated']
        assert not all(checks(faq_expected, faq_response, []).values())
        print('37 runner self-checks passed (not model quality tests)')
        return
    if urllib.parse.urlparse(args.base_url).hostname not in {'localhost', '127.0.0.1'}:
        parser.error('This account-creating runner is restricted to local Docker')
    if not args.output:
        parser.error('--output is required; use a fresh directory for each run')
    args.output.mkdir(parents=True, exist_ok=False)
    dataset_bytes = args.dataset.read_bytes()
    dataset = json.loads(dataset_bytes)
    selected = dataset['cases']
    if args.cases:
        names = set(args.cases.split(','))
        selected = [c for c in selected if c['id'] in names]
        if len(selected) != len(names):
            parser.error('Unknown case ID')
    shop = request(args.base_url, '/shop/'+str(args.shop_id))['body']
    if not shop.get('success'):
        raise RuntimeError('Shop fixture unavailable')
    selected = replace(selected, args.shop_id, shop['data']['name'])
    # Never reuse existing users: cross-conversation vector memory would contaminate cases.
    sql = 'SELECT COUNT(*) FROM tb_user WHERE phone BETWEEN %d AND %d;' % (args.phone_start, args.phone_start+len(selected)-1)
    count = command('docker', 'exec', 'hmdp-mysql', 'sh', '-c',
                    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B dian-ping -e "$1"', '_', sql)
    if count != '0':
        raise RuntimeError('Synthetic phone range already exists; choose a fresh --phone-start')
    container = json.loads(command('docker', 'inspect', 'hm-dianping-ai-service-1'))[0]
    safe_keys = {'LANGCHAIN4J_ENABLED','OPENAI_MODEL','LANGCHAIN4J_EMBEDDING_ENABLED',
                 'OPENAI_EMBEDDING_MODEL','HMDP_AI_RAG_VECTOR_ENABLED','MILVUS_EMBEDDING_DIMENSION',
                 'MILVUS_KNOWLEDGE_COLLECTION','MILVUS_CONVERSATION_COLLECTION',
                 'HMDP_AI_MAX_TOKENS','HMDP_AI_TEMPERATURE','HMDP_AI_HTTP_READ_TIMEOUT',
                 'HMDP_AI_SKILLS_ENABLED','HMDP_AI_SKILLS_MODE','HMDP_AI_ENABLE_THINKING','HMDP_AI_MAX_MODEL_CALLS','HMDP_AI_REQUEST_TIMEOUT','HMDP_AI_RETRY_MAX_ATTEMPTS',
                 'HMDP_AI_TOKEN_BUDGET_PER_REQUEST','HMDP_AI_MAX_TOOL_CALLS','HMDP_AI_CHAT_RATE_LIMIT'}
    config = {k:v for e in container['Config']['Env'] for k,_,v in [e.partition('=')] if k in safe_keys}
    sources = sorted((ROOT/'hm-ai-service/src/main').rglob('*'))
    manifest = {'started_at': datetime.now().astimezone().isoformat(), 'dataset_id':dataset['dataset_id'],
                'dataset_sha256':hashlib.sha256(dataset_bytes).hexdigest(), 'base_url':args.base_url,
                'git_head':command('git','rev-parse','HEAD'), 'git_status':command('git','status','--short'),
                'ai_image_id':container['Image'], 'ai_config_explicit_env':config,
                'trade_image_id':json.loads(command('docker','inspect','hm-dianping-trade-service-1'))[0]['Image'],
                'source_hash_note':'source_sha256 describes workspace files; deployed image IDs are the runtime identity, especially during baseline comparison',
                'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources if p.is_file()},
                'evaluation_scripts_sha256': {name: hashlib.sha256((Path(__file__).parent/name).read_bytes()).hexdigest()
                                             for name in ['run_live.py', 'analyze_live.py']},
                'scenario_count':len(selected), 'turn_count':sum(len(c['turns']) for c in selected),
                'account_isolation':'Fresh local account per scenario; shared only within multi-turn scenario',
                'phone_start':args.phone_start, 'minimum_request_start_gap_s':args.gap,
                'shop_fixture':shop['data'], 'semantic_review':'Not performed by runner'}
    write_json(args.output/'manifest.json', manifest)
    write_json(args.output/'cases.resolved.json', selected)
    write_json(args.output/'reference.json', {'shop':shop, 'vouchers':request(args.base_url, '/voucher/list/'+str(args.shop_id))['body'],
              'hot_blogs':request(args.base_url, '/blog/hot?current=1')['body'],
              'missing_shop':request(args.base_url, '/shop/of/name?name=__LIFEEVAL_NOT_EXIST_7F92__')['body']})
    rows = []
    last_start = 0
    try:
        with (args.output/'turns.jsonl').open('w', encoding='utf-8') as output:
            for case_index, case in enumerate(selected):
                token, user_id = login(args.base_url, str(args.phone_start+case_index))
                conversation = 'eval_'+uuid.uuid4().hex
                try:
                    for turn_index, turn in enumerate(case['turns'], 1):
                        time.sleep(max(0, args.gap-(time.monotonic()-last_start)))
                        payload = {'message':turn['message'], 'conversationId':conversation}
                        if 'shop_id' in turn:
                            payload['shopId'] = turn['shop_id']
                        started_at = datetime.now().astimezone().isoformat()
                        last_start = time.monotonic()
                        response = request(args.base_url, '/ai/customer-service/chat',
                                           None if turn.get('anonymous') else token, payload)
                        request_id = trace_request_id(response)
                        after = request(args.base_url, '/agent/logs/'+conversation+'?requestId='+request_id, token) if request_id else {
                            'http_status': None, 'body': {'data': []}}
                        logs = after['body'].get('data') or []
                        verdicts = checks(turn['expect'], response, logs)
                        # 每个步骤都须与响应同轮；不再用内容变化猜测日志是否新鲜。
                        verdicts['trace_correlation'] = all(entry.get('requestId') == request_id for entry in logs)
                        row = {'case_id':case['id'], 'category':case['category'], 'variant':case.get('variant'),
                               'turn':turn_index, 'user_id':user_id, 'started_at':started_at,
                               'request':payload, 'anonymous':turn.get('anonymous',False), 'expected':turn['expect'],
                               'expected_skill':turn.get('skill'),
                               'rubric':case['rubric'], 'response':response, 'tool_logs':logs,
                               'trace_fetch_http_status':after['http_status'],
                               'trace_request_id':request_id, 'checks':verdicts,
                               'automatic_pass':bool(verdicts) and all(verdicts.values()),
                               'semantic_review':{'status':'unreviewed','reason':None}}
                        output.write(json.dumps(row,ensure_ascii=False)+'\n');output.flush()
                        rows.append(row)
                        write_json(args.output/'summary.json',summarize(rows))
                        print(case['id'], turn_index, response['http_status'],
                              response['body'].get('success'), str(response['elapsed_ms'])+'ms',
                              'PASS' if row['automatic_pass'] else 'FAIL', flush=True)
                        error = response['body'].get('errorMsg') or ''
                        if any(s in error for s in ['未启用', '未配置', '请先登录']):
                            if turn['expect'].get('success') is True:
                                raise RuntimeError('Environment precondition failed: '+error)
                finally:
                    request(args.base_url, '/user/logout', token, method='POST')
    finally:
        manifest['finished_at'] = datetime.now().astimezone().isoformat()
        manifest['completed_turns'] = len(rows)
        manifest['complete'] = len(rows) == manifest['turn_count']
        write_json(args.output/'manifest.json', manifest)
        write_json(args.output/'summary.json', summarize(rows))


if __name__ == '__main__':
    main()
