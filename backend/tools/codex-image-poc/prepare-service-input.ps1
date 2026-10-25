param(
    [Parameter(Mandatory = $true)][string]$SshHost,
    [string]$SshConfig,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$pocRoot = $PSScriptRoot
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $pocRoot ('runs/service-input-' + [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH-mm-ss-fffZ'))
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory for each snapshot' }
$javaSource = [IO.File]::ReadAllText((Join-Path $pocRoot 'PrepareServiceInput.java'))
$remoteSource = [IO.File]::ReadAllText((Join-Path $pocRoot 'prepare-service-input-remote.py'))
$remoteSource = $remoteSource.Replace('__JAVA_SOURCE_BASE64__', [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($javaSource)))
$redactorSource = [IO.File]::ReadAllText((Join-Path $pocRoot 'redact_output.py'))
$remoteSource = $remoteSource.Replace('__REDACTOR_SOURCE_BASE64__', [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($redactorSource)))
$sshArguments = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
if ($SshConfig) { $sshArguments += @('-F', $SshConfig) }
$sshArguments += @($SshHost, 'python3 -')
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$payload = $remoteSource | ssh @sshArguments
if ($LASTEXITCODE -ne 0) { throw 'Service input preparation failed' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$zipPath = Join-Path $OutputDirectory 'input-bundle.zip'
[IO.File]::WriteAllBytes($zipPath, [Convert]::FromBase64String(($payload -join '').Trim()))
Expand-Archive -LiteralPath $zipPath -DestinationPath $OutputDirectory
$inputPath = Join-Path $OutputDirectory 'service-input.json'
$inputData = [IO.File]::ReadAllText($inputPath) | ConvertFrom-Json
[pscustomobject]@{
    Input = [IO.Path]::GetFullPath($inputPath)
    Environment = $inputData.environment
    PromptId = $inputData.promptId
    Provider = $inputData.providerInfo
    Title = $inputData.storyboard.title
    StoryboardElapsedMs = $inputData.storyboardElapsedMs
    Hashes = $inputData.hashes
} | ConvertTo-Json -Depth 5
