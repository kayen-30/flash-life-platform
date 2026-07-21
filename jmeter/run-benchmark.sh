#!/usr/bin/env bash
# 秒杀压测运行脚本:预热 -> loops=1(复现旧报告) -> loops=20(真实吞吐)
set -u
JM="D:/develop/tools/apache-jmeter-5.6.3/bin/jmeter.bat"
JMX="D:/develop/hm-dianping/jmeter/seckill-gateway-stock-100-voucher-14.jmx"
OUT="D:/develop/hm-dianping/jmeter/results"
GW="http://127.0.0.1:10010/voucher-order/seckill/14"
TOKEN=$(head -n1 D:/develop/hm-dianping/jmeter/tokens-1000.txt)

rm -rf "$OUT" && mkdir -p "$OUT"

echo "=== 预热:单请求探活(用第一个 token) ==="
for i in 1 2 3 4 5; do
  code=$(curl -s -o /tmp/warm.txt -w "%{http_code}" -X POST "$GW" \
    -H "authorization: $TOKEN" -H "Content-Type: application/json")
  echo "warmup#$i http=$code body=$(cat /tmp/warm.txt)"
done

run_round () {
  local name=$1 loops=$2 threads=$3 ramp=$4
  echo ""
  echo "=============================================="
  echo "=== 轮次: $name (loops=$loops threads=$threads ramp=${ramp}s) ==="
  echo "=============================================="
  local jtl="$OUT/${name}.jtl"
  local rep="$OUT/${name}-report"
  rm -f "$jtl"; rm -rf "$rep"
  "$JM" -n -t "$JMX" -l "$jtl" -e -o "$rep" \
    -Jloops="$loops" -Jthreads="$threads" -Jramp="$ramp" \
    -Jjmeter.save.saveservice.output_format=csv 2>&1 | \
    grep -iE "summary|err|Tidying|Dashboard|created the tree|Error" | tail -30
}

run_round "reproduce-loops1" 1 1000 1
run_round "throughput-loops20" 20 1000 5

echo ""
echo "=== 全部完成,报告目录: $OUT ==="
