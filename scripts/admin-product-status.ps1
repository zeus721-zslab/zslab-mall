# admin-product-status.ps1 — 운영자 상품 판매 상태 전환(SALE <-> STOPPED·Track 71)
#
# 사용 예시: .\scripts\admin-product-status.ps1 -BaseUrl https://<host> -ProductPublicId prd_XXXXXXXXXXXXXXXXXXXXXXXXXX -Status STOPPED
#
# 동작: 관리자 로그인(POST /api/v1/auth/login·role ADMIN) -> POST /api/v1/admin/products/{id}/sale-status
# 비밀번호는 Read-Host -AsSecureString으로만 받고 토큰·비밀번호는 출력·저장하지 않는다.
# 출력 문자열은 PowerShell 5.1 + BOM 없는 UTF-8 조합에서의 한글 깨짐을 피하기 위해 ASCII로 둔다.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $BaseUrl,
    [Parameter(Mandatory = $true)] [string] $ProductPublicId,
    [Parameter(Mandatory = $true)] [ValidateSet('SALE', 'STOPPED')] [string] $Status,
    [string] $AdminEmail
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

if (-not $AdminEmail) {
    $AdminEmail = Read-Host 'Admin email'
}
$securePassword = Read-Host 'Admin password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

function Get-ErrorSummary($exception) {
    # HTTP 상태·에러 코드만 추출(본문 전체·토큰 미출력).
    $statusCode = ''
    $code = ''
    if ($exception.Response) {
        try { $statusCode = [int] $exception.Response.StatusCode } catch { }
        try {
            $stream = $exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $body = $reader.ReadToEnd() | ConvertFrom-Json
            if ($body.code) { $code = $body.code }
        } catch { }
    }
    return "HTTP $statusCode $code".Trim()
}

# 1) 로그인 -> 토큰(메모리에만 보관)
$token = $null
try {
    $loginBody = @{ email = $AdminEmail; password = $plainPassword; role = 'ADMIN' } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body $loginBody
    $token = $login.token
} catch {
    Write-Output "LOGIN FAILED: $(Get-ErrorSummary $_.Exception)"
    exit 1
} finally {
    $plainPassword = $null
}
if (-not $token) {
    Write-Output 'LOGIN FAILED: no token'
    exit 1
}

# 2) 판매 상태 전환
try {
    $body = @{ status = $Status } | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/admin/products/$ProductPublicId/sale-status" `
        -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $body
    Write-Output "OK: $($result.productPublicId) status=$($result.status)"
    exit 0
} catch {
    Write-Output "FAILED: $(Get-ErrorSummary $_.Exception)"
    exit 1
} finally {
    $token = $null
}
