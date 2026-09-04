param(
    [string]$BaseUrl = "http://localhost:8080/api",
    [string]$Token = $env:AI_TEST_TOKEN
)

$curlArgs = @(
    "-sS",
    "-X", "POST",
    "$BaseUrl/ai/customer-service/chat",
    "-H", "Content-Type: application/json"
)
if ($Token) {
    $curlArgs += @("-H", "Authorization: $Token")
}

$body = '{"message":"平台有什么优惠活动？"}'
$curlArgs += @("-d", $body)
& curl.exe @curlArgs
