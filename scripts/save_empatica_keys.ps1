# Saves the Empatica data-access credentials to a file OUTSIDE the repository.
# Nothing is echoed and nothing is written inside the repo. Run it yourself:
#
#   powershell -ExecutionPolicy Bypass -File scripts\save_empatica_keys.ps1
#
# The file is %USERPROFILE%\.thesis\empatica.env, readable only by your Windows account.

$dir = Join-Path $env:USERPROFILE ".thesis"
$file = Join-Path $dir "empatica.env"
New-Item -ItemType Directory -Force $dir | Out-Null

function Plain($secure) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$keyId  = Plain (Read-Host "Access key ID (hidden)" -AsSecureString)
$secret = Plain (Read-Host "Secret access key (hidden)" -AsSecureString)
$bucket = Read-Host "Data bucket name or URL (as shown in the Empatica portal)"
$prefix = Read-Host "Path prefix inside the bucket, if the portal shows one (Enter to skip)"
$region = Read-Host "AWS region if shown (Enter to skip)"

@(
    "EMPATICA_ACCESS_KEY_ID=$keyId"
    "EMPATICA_SECRET_ACCESS_KEY=$secret"
    "EMPATICA_BUCKET=$bucket"
    "EMPATICA_PREFIX=$prefix"
    "EMPATICA_REGION=$region"
) | Set-Content -Path $file -Encoding ascii

# Only this user may read it.
icacls $file /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
Write-Host "Saved to $file. Nothing was printed or sent anywhere."
