# .env와 .env.example의 활성 키 집합이 같은지 확인한다(D-270). 값은 출력하지 않는다.
# 사용: powershell -File docker/env/check-env-keys.ps1 <env 파일> <example 파일>
# 종료 코드: 0 일치 · 1 불일치(중복 포함) · 2 파일 없음
# 활성 키 = 줄 맨 앞이 KEY= 인 줄의 키 이름. 주석 줄(# KEY=)은 자리 표시라 비교하지 않는다.
# 같은 동작의 POSIX sh 판: docker/env/check-env-keys.sh (출력·종료 코드 동일)
param(
    [string]$EnvFile,
    [string]$ExampleFile
)

$ExitMatch = 0
$ExitMismatch = 1
$ExitMissingFile = 2
$ActiveKeyPattern = '^([A-Za-z_][A-Za-z0-9_]*)='

if (-not $EnvFile -or -not $ExampleFile) {
    Write-Output '사용: powershell -File check-env-keys.ps1 <env 파일> <example 파일>'
    exit $ExitMissingFile
}

foreach ($path in @($EnvFile, $ExampleFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Output "파일 없음: $path"
        exit $ExitMissingFile
    }
}

# 키 이름별 등장 횟수 — 키는 대소문자를 구분한다(sh 판과 같게 Ordinal).
function Get-KeyCounts([string]$Path) {
    $counts = New-Object 'System.Collections.Generic.Dictionary[string,int]' ([System.StringComparer]::Ordinal)
    $fullPath = (Resolve-Path -LiteralPath $Path).Path
    foreach ($line in [System.IO.File]::ReadAllLines($fullPath)) {
        $match = [regex]::Match($line, $ActiveKeyPattern)
        if ($match.Success) {
            $key = $match.Groups[1].Value
            if ($counts.ContainsKey($key)) { $counts[$key] += 1 } else { $counts[$key] = 1 }
        }
    }
    return ,$counts
}

function Join-SortedKeys([System.Collections.Generic.List[string]]$Keys) {
    $sorted = $Keys.ToArray()
    [System.Array]::Sort($sorted, [System.StringComparer]::Ordinal)
    return ($sorted -join ' ')
}

$envCounts = Get-KeyCounts $EnvFile
$exampleCounts = Get-KeyCounts $ExampleFile

$envOnly = New-Object 'System.Collections.Generic.List[string]'
$exampleOnly = New-Object 'System.Collections.Generic.List[string]'
$envDuplicates = New-Object 'System.Collections.Generic.List[string]'
$exampleDuplicates = New-Object 'System.Collections.Generic.List[string]'

foreach ($key in $envCounts.Keys) {
    if (-not $exampleCounts.ContainsKey($key)) { $envOnly.Add($key) }
    if ($envCounts[$key] -gt 1) { $envDuplicates.Add($key) }
}
foreach ($key in $exampleCounts.Keys) {
    if (-not $envCounts.ContainsKey($key)) { $exampleOnly.Add($key) }
    if ($exampleCounts[$key] -gt 1) { $exampleDuplicates.Add($key) }
}

$status = $ExitMatch
if ($envOnly.Count -gt 0) {
    Write-Output (".env에만 있음: " + (Join-SortedKeys $envOnly))
    $status = $ExitMismatch
}
if ($exampleOnly.Count -gt 0) {
    Write-Output ("example에만 있음: " + (Join-SortedKeys $exampleOnly))
    $status = $ExitMismatch
}
if ($envDuplicates.Count -gt 0) {
    Write-Output ("중복(.env): " + (Join-SortedKeys $envDuplicates))
    $status = $ExitMismatch
}
if ($exampleDuplicates.Count -gt 0) {
    Write-Output ("중복(example): " + (Join-SortedKeys $exampleDuplicates))
    $status = $ExitMismatch
}

if ($status -eq $ExitMatch) {
    Write-Output ("키 일치 (" + $envCounts.Count + "개)")
}
exit $status
