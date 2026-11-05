# Run on Windows after building and enabling autostart in Lightscreen.
# Example: .\check-autostart.ps1 -ExePath 'C:\Program Files\Lightscreen\lightscreen.exe'
param([Parameter(Mandatory = $true)][string]$ExePath)

$ErrorActionPreference = 'Stop'
$resolvedExe = (Resolve-Path -LiteralPath $ExePath).Path
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$actual = (Get-ItemProperty -Path $runKey -Name 'Lightscreen').Lightscreen
$expected = '"' + $resolvedExe + '"'
if (($actual -cne $expected) -and ($actual -cne ($expected + ' -h'))) {
    throw "Incorrect Windows Run value. Expected '$expected' (optionally -h), got '$actual'."
}
Write-Host "PASS: startup command is quoted: $actual"
$approvedKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run'
if (Test-Path -LiteralPath $approvedKey) {
    $approval = (Get-ItemProperty -LiteralPath $approvedKey -Name 'Lightscreen' -ErrorAction SilentlyContinue).Lightscreen
    if ($approval -is [byte[]] -and $approval.Length -gt 0 -and $approval[0] -eq 3) {
        Write-Warning 'Windows Startup Apps marks Lightscreen as disabled; enable it there.'
    }
}
Write-Host 'Sign out and sign back in to confirm the app launches into the notification area.'
