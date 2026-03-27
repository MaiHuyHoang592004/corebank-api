param(
    [string]$OutputPath = "showcase-output/latest-showcase-report.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

$testClasses = @(
    "PaymentApplicationServiceIntegrationTest",
    "PaymentIdempotencyIntegrationTest",
    "TransferServiceIntegrationTest",
    "DepositApplicationServiceIntegrationTest",
    "LoanApplicationServiceIntegrationTest",
    "OutboxPatternIntegrationTest",
    "DepositRateLimitIntegrationTest",
    "IdempotencyRedisReplayCacheIntegrationTest"
)

$testSelector = [string]::Join(',', $testClasses)
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"
$commitSha = (git rev-parse HEAD).Trim()
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
$porcelainStatus = git status --porcelain
$gitState = if ([string]::IsNullOrWhiteSpace(($porcelainStatus -join ""))) { "clean" } else { "dirty" }

$outputFile = Join-Path $repoRoot $OutputPath
$outputDir = Split-Path -Parent $outputFile
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}
$mavenCommand = if (Test-Path (Join-Path $repoRoot ".mvn\wrapper\maven-wrapper.properties")) {
    Join-Path $repoRoot "mvnw.cmd"
} else {
    "mvn"
}

$surefireDir = Join-Path $repoRoot "target/surefire-reports"
if (Test-Path $surefireDir) {
    foreach ($testClass in $testClasses) {
        Get-ChildItem -Path $surefireDir -Filter "*$testClass*" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

$start = Get-Date
$mavenOutput = @()
$exitCode = 0
try {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $mavenOutput = & $mavenCommand "-Dtest=$testSelector" "test" 2>&1
    $ErrorActionPreference = $previousErrorActionPreference
    if ($LASTEXITCODE -ne 0) {
        $exitCode = $LASTEXITCODE
    }
} catch {
    $ErrorActionPreference = $previousErrorActionPreference
    $exitCode = 1
    $mavenOutput += $_ | Out-String
}
$end = Get-Date
$duration = [math]::Round(($end - $start).TotalMinutes, 2)
$mdTick = '`'

$reportLines = New-Object System.Collections.Generic.List[string]
$reportLines.Add("# CoreBank Showcase Report")
$reportLines.Add("")
$reportLines.Add("- Timestamp: $timestamp")
$reportLines.Add("- Commit SHA: $mdTick$commitSha$mdTick")
$reportLines.Add("- Branch: $mdTick$branch$mdTick")
$reportLines.Add("- Git state: $mdTick$gitState$mdTick")
$reportLines.Add("- Duration (minutes): $mdTick$duration$mdTick")
$reportLines.Add("- Maven exit code: $mdTick$exitCode$mdTick")
$reportLines.Add("")
$reportLines.Add("## Executed Test Suite")
foreach ($testClass in $testClasses) {
    $reportLines.Add("- $mdTick$testClass$mdTick")
}
$reportLines.Add("")

$summaryItems = @()
$allPassed = $exitCode -eq 0
foreach ($testClass in $testClasses) {
    $xmlPath = Join-Path $repoRoot "target/surefire-reports/TEST-com.corebank.corebank_api.*.$testClass.xml"
    $matches = Get-ChildItem -Path $xmlPath -ErrorAction SilentlyContinue
    if (-not $matches) {
        $summaryItems += [pscustomobject]@{ Name = $testClass; Status = "MISSING"; Detail = "report not found" }
        $allPassed = $false
        continue
    }

    [xml]$xml = Get-Content $matches[0].FullName
    $suite = $xml.testsuite
    $failures = [int]$suite.failures
    $errors = [int]$suite.errors
    $skipped = [int]$suite.skipped
    $tests = [int]$suite.tests
    if ($failures -eq 0 -and $errors -eq 0) {
        $summaryItems += [pscustomobject]@{ Name = $testClass; Status = "PASSED"; Detail = "$tests tests, $skipped skipped" }
    } else {
        $summaryItems += [pscustomobject]@{ Name = $testClass; Status = "FAILED"; Detail = "$tests tests, $failures failures, $errors errors, $skipped skipped" }
        $allPassed = $false
    }
}

$reportLines.Add("## Pass/Fail Summary")
$overallStatus = if ($allPassed) { "PASSED" } else { "FAILED" }
$reportLines.Add("- Overall status: $mdTick$overallStatus$mdTick")
foreach ($item in $summaryItems) {
    $reportLines.Add("- ${mdTick}$($item.Name)${mdTick}: ${mdTick}$($item.Status)${mdTick} ($($item.Detail))")
}
$reportLines.Add("")
$reportLines.Add("## Interview Claim Mapping")
$reportLines.Add("1. Payment semantics")
$reportLines.Add("   Proven by $mdTick" + "PaymentApplicationServiceIntegrationTest" + "$mdTick and $mdTick" + "PaymentIdempotencyIntegrationTest" + "$mdTick.")
$reportLines.Add("2. Transfer and idempotency safety")
$reportLines.Add("   Proven by $mdTick" + "TransferServiceIntegrationTest" + "$mdTick.")
$reportLines.Add("3. Deposit and lending lifecycle depth")
$reportLines.Add("   Proven by $mdTick" + "DepositApplicationServiceIntegrationTest" + "$mdTick and $mdTick" + "LoanApplicationServiceIntegrationTest" + "$mdTick.")
$reportLines.Add("4. Redis acceleration without truth ownership")
$reportLines.Add("   Proven by $mdTick" + "DepositRateLimitIntegrationTest" + "$mdTick and $mdTick" + "IdempotencyRedisReplayCacheIntegrationTest" + "$mdTick.")
$reportLines.Add("")
$reportLines.Add("## Notes")
if ($allPassed) {
    $reportLines.Add("- This run supports the interview claim that the repo can be demonstrated through one command plus one evidence report.")
} else {
    $reportLines.Add("- One or more showcase tests failed. Review $mdTick" + "target/surefire-reports/" + "$mdTick and rerun after fixing the issue.")
}

Set-Content -Path $outputFile -Value $reportLines -Encoding UTF8

if ($exitCode -ne 0) {
    Write-Host ($mavenOutput | Out-String)
    throw "Showcase runner failed. See $OutputPath and target/surefire-reports/."
}

Write-Host "Showcase report written to $OutputPath"
